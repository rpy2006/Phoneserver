package com.yadaventerprise.phoneserver;

public class ApiEndpoint {
    public String path;   // e.g. "/api/hello"
    public String json;   // response body

    public ApiEndpoint(String path, String json) {
        this.path = path;
        this.json = json;
    }
}
