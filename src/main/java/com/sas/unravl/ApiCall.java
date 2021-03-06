package com.sas.unravl;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sas.unravl.assertions.BaseUnRAVLAssertion;
import com.sas.unravl.assertions.StatusAssertion;
import com.sas.unravl.assertions.UnRAVLAssertion;
import com.sas.unravl.assertions.UnRAVLAssertion.Stage;
import com.sas.unravl.assertions.UnRAVLAssertionException;
import com.sas.unravl.auth.UnRAVLAuth;
import com.sas.unravl.extractors.UnRAVLExtractor;
import com.sas.unravl.generators.Binary;
import com.sas.unravl.generators.JsonRequestBodyGenerator;
import com.sas.unravl.generators.UnRAVLRequestBodyGenerator;
import com.sas.unravl.util.Json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Encapsulate a runtime call to an API, as specified by an UnRAVL script. This
 * runs env bindings, preconditions, generates the request body, if any, and
 * uses the request headers, HTTP method, and URI defined by the UnRAVL script,
 * calls the API, and stores the HTTP status, response headers, and response
 * body, then binds results as per extractors defined in the script, and runs
 * assertions.
 *
 * @author sasdjb
 */
public class ApiCall {

    private static final String AUTHORIZATION = "Authorization";
    private static final String MASK = "************";
    private static final Logger logger = Logger.getLogger(ApiCall.class);
    private static final String JSON_GENERATOR_KEY = "json";

    private UnRAVL script;
    /**
     * @deprecated use requestStream instead
     */
    private ByteArrayOutputStream requestBody;
    private ByteArrayOutputStream responseBody;
    private InputStream requestStream;

    private int httpStatus;
    private Header responseHeaders[];

    private List<UnRAVLAssertion> passedAssertions, failedAssertions,
            skippedAssertions;
    private UnRAVLException exception;
    private Method method;
    private String uri;
    private boolean cancelled = false, skipped = false;

    private static final ObjectNode STATUS_ASSERTION = new ObjectNode(
            JsonNodeFactory.instance);

    static {
        STATUS_ASSERTION.set("status", new TextNode("2.."));
    }

    public ApiCall(UnRAVL script) throws UnRAVLException, IOException {
        this.script = script;

        passedAssertions = new ArrayList<UnRAVLAssertion>();
        failedAssertions = new ArrayList<UnRAVLAssertion>();
        skippedAssertions = new ArrayList<UnRAVLAssertion>();

        script.getRuntime().addApiCall(this);
    }

    public ApiCall run() throws UnRAVLException {
        try {
            if (getScript().isRunnable() && conditionalExecution()) {
                defineEnv();
                bind("unravlScript", getScript());
                if (runAssertions(UnRAVLAssertion.Stage.PRECONDITIONS)) {
                    defineBody();
                    executeAPI();
                    extract();
                    runAssertions(UnRAVLAssertion.Stage.ASSERT);
                }             }
        } catch (UnRAVLException e) {
            throwException(e);
        } catch (IOException e) {
            throwException(e);
        }
        return this;
    }

    private boolean canceled() {
        cancelled = cancelled || getScript().getRuntime().isCanceled();
        return cancelled;
    }
    
    public boolean wasCancelled() {
        return cancelled;
    }

    public boolean wasSkipped() {
        return skipped;
    }
    

    private boolean conditionalExecution() throws UnRAVLException {
        Boolean cond = conditionalExecution(getScript());
        if (cond == null)
            cond = Boolean.valueOf(getRuntime().getFailedAssertionCount() == 0);
        skipped = !cond.booleanValue();
        return cond.booleanValue();
    }

    private Boolean conditionalExecution(UnRAVL script) throws UnRAVLException {
        if (script == null)
            return null;
        Boolean inherited = conditionalExecution(script.getTemplate());
        if (inherited != null && !inherited.booleanValue())
            return Boolean.FALSE;
        JsonNode cond = script.getRoot().get("if");
        if (cond == null)
            return inherited;
        Object condition = null;
        if (cond.isBoolean())
            condition = (BooleanNode) cond;
        else if (cond.isTextual()) {
            String condS = cond.textValue();
            if (getScript().bound(condS))
                condition = getScript().binding(condS);
            else {
                try {
                    condition = script.eval(condS);
                } catch (RuntimeException e) {
                    logger.error("script '" + condS
                            + "' threw a runtime exception "
                            + e.getClass().getName() + ", " + e.getMessage());
                    throw new UnRAVLException(e.getMessage(), e);
                }
            }
        }
        if (condition instanceof Boolean)
            return (Boolean) condition;
        else if (condition instanceof BooleanNode)
            return Boolean.valueOf(((BooleanNode) condition).booleanValue());
        else {
            throw new UnRAVLException(
                    "Invalid condition value for if condition: " + cond);
        }
    }

    private void authenticate() throws UnRAVLException, IOException {
        authenticate(script);
    }

    private void authenticate(UnRAVL script) throws UnRAVLException,
            IOException {
        if (script == null)
            return;

        JsonNode auth = script.getRoot().get("auth");
        if (auth == null) {
            authenticate(script.getTemplate()); // recurse on template
            return;
        }
        if (auth.isBoolean()) {
            if (auth.booleanValue()) {
                throw new UnRAVLException(
                        "\"auth\" : true is invalid. Only \"auth\" : false is allowed (to disable inherited authentication.)");
            } else {
                logger.info("authentication disabled in script.");
                return;
            }
        }
        ObjectNode spec = null;
        // If "auth" value is just a string and not an object, such as "auth" :
        // "basic",
        // convert to "auth" : { "basic" : true } to enable that auth type
        if (auth.isTextual()) {
            spec = Json.jsonNodeFactory().objectNode();
            spec.put(auth.textValue(), true);
        } else
            spec = Json.object(auth);
        String authKey = Json.firstFieldName(spec);
        Class<? extends UnRAVLAuth> authClass = getPlugins().getAuth().get(
                authKey);
        try {
            UnRAVLAuth authInstance = authClass.newInstance();
            authInstance.authenticate(getScript(), spec, this);
        } catch (InstantiationException e) {
            throw new UnRAVLException(
                    "Could not instantiate authentication plugin for " + auth);
        } catch (IllegalAccessException e) {
            throw new UnRAVLException(
                    "Could not instantiate authentication plugin for " + auth);
        }

    }

    private void defineBody() throws UnRAVLException, IOException {
        defineBody(script);
    }

    private void defineBody(UnRAVL script) throws UnRAVLException, IOException {
        if (canceled() || script == null)
            return;
        JsonNode body = script.getRoot().get("body");

        if (body == null || body.isNull()) {
            defineBody(script.getTemplate());
            return;
        }
        if (body.isTextual() && !isVariableHoldingJson(body.asText())) {
            String s = script.expand(body.asText());
            if (!s.trim().startsWith(UnRAVL.REDIRECT_PREFIX)) {
                try {
                    requestStream = new ByteArrayInputStream(
                            s.getBytes("UTF-8"));
                } catch (IOException e) {
                    throw new UnRAVLException(
                            "Could not encode string using UTF-8 for body "
                                    + body);
                }
                return;
            }
        }

        ObjectNode bodyObj = null;
        String generatorKey = null;
        Class<? extends UnRAVLRequestBodyGenerator> bgClass = null;

        if (body.isObject() && body.fields().hasNext()) {
            bodyObj = Json.object(body);
            generatorKey = body.fields().next().getKey();
            bgClass = getPlugins().getBodyGenerators().get(generatorKey);

        }

        if (bgClass == null || body.isArray() || body.isTextual()) {
            bodyObj = new ObjectNode(JsonNodeFactory.instance);
            bodyObj.set(JSON_GENERATOR_KEY, body);
            generatorKey = JSON_GENERATOR_KEY;
            bgClass = JsonRequestBodyGenerator.class;
        }

        try {
            UnRAVLRequestBodyGenerator gen = bgClass.newInstance();
            requestStream = gen.getBody(script, bodyObj, this);
        } catch (InstantiationException e) {
            throw new UnRAVLException(
                    "Could not instantiate body generator plugin for " + body);
        } catch (IllegalAccessException e) {
            throw new UnRAVLException(
                    "Could not instantiate body generator plugin for " + body);
        }

    }

    private boolean isVariableHoldingJson(String value) {
        if (value != null && !value.startsWith(UnRAVL.REDIRECT_PREFIX)) {
            Object ref = script.binding(value);
            if (ref instanceof JsonNode) {
                return true;
            }
        }
        return false;
    }

    private void extract() throws UnRAVLException {
        extract(script);
    }

    private void extract(UnRAVL script) throws UnRAVLException {
        try {
            if (canceled() || script == null)
                return;
            extract(script.getTemplate());
            JsonNode bind = script.getRoot().get("bind");
            if (bind == null)
                return;
            if (bind.isObject()) {
                bind = Json.wrapInArray(bind);
            }
            for (JsonNode j : Json.array(bind)) {
                if (canceled())
                    return;
                ObjectNode ob = Json.object(j);
                Map.Entry<String, JsonNode> first = Json.firstField(ob);
                String key = first.getKey();
                Class<? extends UnRAVLExtractor> ec = getPlugins()
                        .getExtractors().get(key);
                if (ec == null)
                    if (!bind.isObject())
                        throw new UnRAVLException("No defined extractor " + key);
                UnRAVLExtractor ex;
                try {
                    ex = ec.newInstance();
                    ex.extract(script, ob, this);
                } catch (InstantiationException e1) {
                    throw new UnRAVLException(
                            "Could not instantiate extractor " + key
                                    + " using class " + ec.getName(), e1);
                } catch (IllegalAccessException e1) {
                    throw new UnRAVLException(
                            "Could not instantiate extractor " + key
                                    + " using class " + ec.getName(), e1);
                } catch (RuntimeException e1) {
                    throw new UnRAVLException(e1.getMessage(), e1);
                }
            }
        } finally {
            // Must do this after making more bindings
            getRuntime().resetBindings();
        }
    }

    private ObjectNode statusAssertion(UnRAVL script) throws UnRAVLException {
        return UnRAVL.statusAssertion(script);
    }

    public Header getResponseHeader(String headerName) {
        for (Header h : responseHeaders) {
            if (h.getName().equalsIgnoreCase(headerName))
                return h;
        }
        return null;
    }

    // read all the values in "env" and bind them to this instance's env
    // Scalars are bound as Java scalar types; JSON arrays and objects
    // are bound as JsonNode objects
    private void defineEnv() throws UnRAVLException {
        defineEnv(getScript());
    }

    static void defineEnv(UnRAVL script) throws UnRAVLException {
        if (script == null)
            return;
        defineEnv(script.getTemplate());
        JsonNode envNode = script.getRoot().get("env");
        if (script.getName() != null)
            script.bind("name", script.getName());
        if (envNode != null) {
            for (Map.Entry<String, JsonNode> e : Json.fields(envNode)) {
                String name = e.getKey();
                JsonNode n = e.getValue();
                Object value = null;
                if (n.isValueNode()) {
                    JsonToken t = n.asToken();
                    switch (t) {
                    case VALUE_FALSE:
                        value = Boolean.FALSE;
                        break;
                    case VALUE_TRUE:
                        value = Boolean.TRUE;
                        break;
                    case VALUE_NULL:
                        value = null;
                        break;
                    case VALUE_NUMBER_FLOAT:
                        value = new Double(n.toString());
                        break;
                    case VALUE_NUMBER_INT:
                        value = new Long(n.toString());
                        break;
                    case VALUE_STRING:
                        value = script.expand(n.textValue());
                        break;
                    default:
                        value = n;
                        break;
                    }
                } else {
                    value = Json.expand(n, script);
                }
                script.bind(name, value);
            }

        }

    }

    /**
     * Remove a binding from this script's environment. After this,
     * {@link #getVariable(String)} will return null and {@link #bound(String)}
     * will return false
     *
     * @param key
     *            the var name
     * @see #bind(String, Object)
     * @see #bound(String)
     */
    public void unbind(String key) {
        getRuntime().unbind(key);
    }

    /**
     * Return the value of a variable from this script's environment
     *
     * @param key
     *            the variable name
     * @return the bound value, or null
     * @see #bind(String, Object)
     * @see #unbind(String)
     * @see #bound(String)
     */
    public Object getVariable(String key) {
        return getRuntime().binding(key);
    }

    /**
     * Test if a variable is bound in this script's environment
     *
     * @param key
     *            the variable name
     * @return true if the variable is bound, else false
     * @see #bind(String, Object)
     * @see #unbind(String)
     */
    public boolean bound(String key) {
        return getRuntime().bound(key);
    }

    public InputStream getResponseBodyAsInputStream() {
        ByteArrayInputStream i = new ByteArrayInputStream(getResponseBody()
                .toByteArray());
        return i;
    }

    public void executeAPI() throws UnRAVLException {
        if (canceled()) {
            cancelled = true;
            return;
        }
        if (script.getMethod() == null || script.getURI() == null) {
            logger.warn("Warning: Non-template script " + script.getName()
                    + " does not define an HTTP method or URI.");
            return;
        }
        setMethod(script.getMethod());
        RestTemplate restTemplate = getRuntime().getPlugins().getRestTemplate();
        executeAPIWithRestTemplate(restTemplate);
    }

    private void executeAPIWithRestTemplate(RestTemplate restTemplate)
            throws UnRAVLException {
        // authenticate first, since this may add new (Authentication) headers.
        // Set the expanded URI first, since auth nodes may access it
        setURI(script.expand(script.getURI()));
        try {
            authenticate();
        } catch (IOException e) {
            throwException(e);
        }
        // expand the URI after authenticating: OAuth2 or other auth may set env
        // vars that should
        // be expanded in the URI
        setURI(script.expand(getURI()));
        // Use RequestCallback and ResponseExtractor
        // to handle all request bodies, including binary.
        // RestTemplate.exchange can't handle binary byte[] body

        final RequestCallback requestCallback = new RequestCallback() {

            @Override
            public void doWithRequest(final ClientHttpRequest request)
                    throws IOException {
                final HttpHeaders headers = mapHeaders(script
                        .getRequestHeaders());
                request.getHeaders().putAll(headers);
                if (requestStream != null)
                    Binary.copy(requestStream, request.getBody());
                ;
            }
        };
        final ResponseExtractor<InternalResponse> responseExtractor = new ResponseExtractor<InternalResponse>() {
            @Override
            public InternalResponse extractData(ClientHttpResponse response)
                    throws IOException {
                httpStatus = response.getStatusCode().value();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Binary.copy(response.getBody(), baos);
                return new InternalResponse(response.getStatusCode(),
                        baos.toByteArray(), response.getHeaders());
            }
        };

        long start = System.currentTimeMillis();
        try {
            logger.info(method.name() + " " + getURI());
            // create response body and a valid HTTP response code before the
            // call
            // so that even on exceptions, we have a non-null response
            responseBody = new ByteArrayOutputStream();
            httpStatus = HttpStatus.NOT_IMPLEMENTED.value();
            InternalResponse response = restTemplate.execute(getURI(),
                    HttpMethod.valueOf(method.name()), requestCallback,
                    responseExtractor);
            setResponseHeaders(mapHeaders(response.headers));
            responseBody.write(response.responseBody);
            responseBody.close();
            long end = System.currentTimeMillis();
            logger.info(script.getMethod() + " took " + (end - start)
                    + "ms, returned HTTP status " + response.status);
            log("Response body:", responseBody, "Response headers:",
                    response.headers);
            assertStatus(httpStatus);
        } catch (IOException e) {
            throwException(e);
        } catch (HttpStatusCodeException e) {
            // this happens if the host name cannot be resolved.
            // This and other exceptions below won't happen with the
            // default RestTemplate created in UnRAVLPlugins, but may
            // occur if the client injects their own RestTemplate
            // instance that uses the default error handler which
            // throws exceptions.
            assertStatus(e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // execute can also throw ResourceAccessException if host does not
            // resolve.
            assertStatus(httpStatus);
        } catch (RestClientException e) {
            // execute can also throw RestClientException
            // but that exception does not convey a HTTP status code.
            // We assume 400 if we get RestClientException
            assertStatus(httpStatus);
        } catch (RuntimeException e) { // Spring RestTemplate can
                                       // throw NestedRuntimeException
            throwException(e);
        }

    }

    private class InternalResponse {
        private HttpStatus status;
        private byte[] responseBody;
        private HttpHeaders headers;

        public InternalResponse(HttpStatus status, byte[] responseBody,
                HttpHeaders headers) {
            super();
            this.status = status;
            this.responseBody = responseBody;
            this.headers = headers;
        }
    }

    // Convert from Apache Headers Spring Headers
    private HttpHeaders mapHeaders(List<Header> requestHeaders) {
        HttpHeaders headers = new HttpHeaders();
        for (Header h : requestHeaders) {
            String value = getScript().expand(h.getValue());
            logger.info(String.format("Request header: %s: %s", h.getName(),
                    possiblyMaskedHeaderValue(h)));
            headers.add(h.getName(), value);
        }
        return headers;
    }

    // Convert from Spring Headers to Apache Headers
    private Header[] mapHeaders(HttpHeaders responseHeaders) {
        ArrayList<Header> h = new ArrayList<Header>(responseHeaders.size());
        for (Entry<String, List<String>> es : responseHeaders.entrySet()) {
            String name = es.getKey();
            for (String v : es.getValue()) {
                Header header = new BasicHeader(name, v);
                h.add(header);
            }
        }
        return h.toArray(new Header[h.size()]);
    }

    private void setMethod(Method method) {
        this.method = method;
    }

    public void setURI(String uri) {
        this.uri = uri;
    }

    public String getURI() {
        return uri;
    }

    public Method getMethod() {
        return method;
    }

    public void bind(String varName, Object value) {
        getRuntime().bind(varName, value);
    }

    /**
     * @return the request body, wrapped in a ByteArrayOutputStream
     * @deprecated Use getRequestStream() instead
     */
    public ByteArrayOutputStream getRequestBody() {
        if (requestBody == null) {
            if (requestStream == null)
                return null;
            requestBody = new ByteArrayOutputStream();
            try {
                Binary.copy(requestStream, requestBody);
            } catch (IOException e) {
                logger.error(e);
            }
            requestStream = null;
        }
        return requestBody;
    }

    public InputStream getRequestStream() {
        return requestStream;
    }

    public ByteArrayOutputStream getResponseBody() {
        return responseBody;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public Header[] getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Header responseHeaders[]) {
        this.responseHeaders = responseHeaders;
    }

    // Check that the httpStatusCode matches the expected status
    // code of the API call. If no explicit status assertion exists,
    // assert the status is a 2xx code.
    private void assertStatus(int httpStatusCode)
            throws UnRAVLAssertionException, UnRAVLException {
        this.httpStatus = httpStatusCode;
        StatusAssertion sa = new StatusAssertion();
        sa.setScript(script);
        sa.setScriptlet(STATUS_ASSERTION);
        try {
            bind("status", Integer.valueOf(httpStatusCode));
            ObjectNode node = statusAssertion();
            if (node != null) {
                sa.check(script, node, UnRAVLAssertion.Stage.ASSERT, this);
            } else {
                if (httpStatusCode < 200 || httpStatusCode >= 300)
                    throw new UnRAVLAssertionException("http status "
                            + httpStatusCode + " not a 2xx status.");
            }
        } catch (UnRAVLAssertionException e) {
            failedAssertions.add(sa);
            throw e;
        }
    }

    public void setException(UnRAVLException exception) {
        this.exception = exception;
    }

    public UnRAVLException getException() {
        return exception;
    }

    /**
     * Wrap exception in an UnRAVLException (unless it already is one), then
     * throw the UnRAVLException
     *
     * @param exception
     *            an exception
     * @throws UnRAVLException
     *             the wrapped exception
     */
    public void throwException(Exception exception) throws UnRAVLException {
        if (exception instanceof UnRAVLException)
            setException((UnRAVLException) exception);
        else
            setException(new UnRAVLException(exception));
        throw getException();
    }

    public UnRAVLRuntime getRuntime() {
        return script.getRuntime();
    }

    private ObjectNode statusAssertion() throws UnRAVLException {
        return statusAssertion(script);
    }

    private boolean runAssertions(Stage stage) throws UnRAVLException {
        return runAssertions(script, stage);
    }

    private boolean runAssertions(UnRAVL unravl, Stage stage)
            throws UnRAVLException {
        if (canceled() || unravl == null)
            return true;
        if (!runAssertions(unravl.getTemplate(), stage))
            return false;
        if (canceled())
            return true;
        JsonNode assertionNode = unravl.getRoot().get(stage.getName());
        if (assertionNode == null)
            return true;
        ArrayNode assertions = assertionArray(assertionNode, stage);
        for (int i = 0; !canceled() && i < assertions.size(); i++) {
            JsonNode s = assertions.get(i);
            ObjectNode assertionScriptlet = null;
            UnRAVLAssertion a = null;
            Class<? extends UnRAVLAssertion> aClass = null;
            try {
                if (s.isTextual()) {
                    ObjectNode o = new ObjectNode(JsonNodeFactory.instance);
                    o.set(getRuntime().getScriptLanguage(), (TextNode) s);
                    s = o;
                }
                String aName = Json.firstFieldName(s);
                assertionScriptlet = Json.object(s);
                aClass = getPlugins().getAssertions().get(aName);
                if (aClass == null)
                    throw new UnRAVLException(
                            "No such assertion class registered for " + stage
                                    + " keyword " + aName);
                a = aClass.newInstance();
                a.setAssertion(assertionScriptlet);
                a.check(this.script, assertionScriptlet, stage, this);
                passedAssertions.add(a);
            } catch (InstantiationException e) {
                failedAssertions.add(BaseUnRAVLAssertion.of(script,
                        assertionScriptlet));
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                failedAssertions.add(BaseUnRAVLAssertion.of(script,
                        assertionScriptlet));
                logger.error(e.getMessage());
                throw new UnRAVLException("Assertion class " + aClass.getName()
                        + " cannot be instantiated.");
            } catch (UnRAVLAssertionException e) {
                failedAssertions.add(a);
                for (int j = i + 1; j < assertions.size(); j++) {
                    JsonNode skipped = assertions.get(j);
                    if (skipped.isTextual()) {
                        ObjectNode g = new ObjectNode(JsonNodeFactory.instance);
                        g.set("groovy", (TextNode) skipped);
                        skipped = g;
                    }
                    skippedAssertions.add(BaseUnRAVLAssertion.of(getScript(),
                            Json.object(skipped)));
                }
                throw e;
            }

        }
        return failedAssertions.size() == 0;
    }

    private UnRAVLPlugins getPlugins() {
        return getRuntime().getPlugins();
    }

    /**
     * Convert the value of an assert or preconditions into an array.
     *
     * @param val
     *            the "assert" or "preconditions" scriptlet
     * @param stage
     *            which stage is running
     * @return an array of JSON assertions. If the input scriptlet value is a
     *         string or an object, embed it in this array. If the value is
     *         already an array, return it.
     * @throws UnRAVLException
     *             if the value of the assertion node is not one of the three
     *             expected forms.
     */
    public static ArrayNode assertionArray(JsonNode val, Stage stage)
            throws UnRAVLException {
        if (val == null)
            return null;
        if (val.isArray())
            return (ArrayNode) val;
        if (val.isTextual())
            return Json.wrapInArray(val);
        if (val.isObject())
            return Json.wrapInArray(val);
        throw new UnRAVLException("Value of " + stage.getName()
                + " must be a string, an object, or an array.");
    }

    private void log(String bodyLabel, ByteArrayOutputStream bytes,
            String headersLabel, HttpHeaders headers) {

        if (headers != null && headers.size() > 0) {
            logger.info(headersLabel);
            Header hs[] = mapHeaders(headers);
            for (Header h : hs) {
                // Don't log easily decoded credentials
                logger.info(h.getName() + ": " + possiblyMaskedHeaderValue(h));
            }
        }
        MediaType contentType = headers.getContentType();
        if (contentType == null)
            return;
        Header ct[] = new Header[] { new BasicHeader("Content-Type",
                contentType.toString()) };
        if (script.bodyIsTextual(ct))
            try {
                if (bytes == null || bytes.size() == 0) {
                    if (getMethod() != Method.HEAD)
                        logger.warn("Warning: Non-HEAD request returned a text Content-Type header but defines no body.");
                    return;
                }
                if (logger.isInfoEnabled()) {
                    logger.info(bodyLabel);
                    if (script.bodyIsJson(ct) && bytes.size() > 0) {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            mapper.enable(SerializationFeature.INDENT_OUTPUT);
                            JsonNode json = Json.parse(bytes.toString("UTF-8"));
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            os.write(mapper.writeValueAsBytes(json));
                            os.close();
                            bytes = os;
                        } catch (UnRAVLException e) {
                            // ignore parse/format errors; just print bytes w/o
                            // pretty print.
                        }
                    }
                    bytes.writeTo(System.out);
                    System.out.println();
                }
            } catch (IOException e) {
                logger.error(e);
            }
    }

    private String possiblyMaskedHeaderValue(Header h) {
        return h.getName().equalsIgnoreCase(AUTHORIZATION) ? MASK : h
                .getValue();
    }

    public UnRAVL getScript() {
        return script;
    }

    public List<UnRAVLAssertion> getPassedAssertions() {
        return passedAssertions;
    }

    public List<UnRAVLAssertion> getFailedAssertions() {
        return failedAssertions;
    }

    public List<UnRAVLAssertion> getSkippedAssertions() {
        return skippedAssertions;
    }

    /**
     * Print a report of the API call to a print stream (System.out)
     * 
     * @param out
     *            the report destination
     */
    public void report(PrintStream out) {
        UnRAVL script = getScript(); // @formatter:off
        String title = "Script '"
                + script.getName()
                + "' "
                + (getMethod() == null ? "<no method>" : getMethod().toString())
                + " " + (getURI() == null ? "<no URI>" : getURI()); // @formatter:on
        out.println();
        for (int i = title.length(); i > 0; i--)
            out.print('-');
        out.println();
        out.println(title);

        if (getException() != null) {
            out.println("Caught exception running test " + title);
            out.println(getException().getMessage());
        }
        report(getPassedAssertions(), "Passed", out);
        report(getFailedAssertions(), "Failed", out);
        report(getSkippedAssertions(), "Skipped", out);
        if (wasCancelled())
            out.println("This call was cancelled.");
        if (wasSkipped())
            out.println("This call was skipped because preconditions.");

        out.flush();
    }

    private void report(List<UnRAVLAssertion> as, String label, PrintStream out) {
        out.println(as.size() + " " + label + ":");
        if (as.size() > 0) {
            for (UnRAVLAssertion a : as) {
                out.println(label + " " + a.getStage().getName() + " " + a);
                out.flush();
                UnRAVLAssertionException e = a.getUnRAVLAssertionException();
                if (e != null) {
                    out.println(e.getClass().getName() + " " + e.getMessage());
                }
            }
        }
    }
}