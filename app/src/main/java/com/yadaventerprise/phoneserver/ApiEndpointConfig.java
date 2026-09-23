package com.yadaventerprise.phoneserver;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the user can define for one custom /api/* endpoint:
 * which path and method it responds to, extra response headers, documented
 * (informational only, not enforced) parameters, and the JSON response body.
 */
public class ApiEndpointConfig {
    public String path;    // normalized to start with "/api/", e.g. "/api/hello"
    public String method = "GET";
    public List<KeyValue> headers = new ArrayList<>();
    public List<KeyValue> params = new ArrayList<>();
    public String body = "{}";
}
