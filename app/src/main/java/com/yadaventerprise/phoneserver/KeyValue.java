package com.yadaventerprise.phoneserver;

/** A simple key/value pair - used for header and parameter rows in the API editor. */
public class KeyValue {
    public String key;
    public String value;

    public KeyValue() {
    }

    public KeyValue(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
