package org.apache.coyote.http11;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class HttpResponse {

    private static final String PROTOCOL = "HTTP/1.1";
    private final Map<String, String> attribute = new HashMap<>();
    private final Integer status;
    private final String code;
    private final String responseBody;

    public HttpResponse(
            Integer status,
            String code
    ) {
        this(status, code, "");
    }

    public HttpResponse(
            Integer status,
            String code,
            String responseBody
    ) {
        this.status = status;
        this.code = code;
        this.responseBody = responseBody;
    }

    public void setAttribute(String key, String value) {
        attribute.put(key, value);
    }

    public String toString() {
        return String.join("\r\n",
                PROTOCOL + " " + status + " " + code + " ",
                attribute.entrySet()
                        .stream()
                        .map(entry -> entry.getKey() + ": " + entry.getValue() + " ")
                        .collect(Collectors.joining("\r\n")),
                "",
                responseBody
        );
    }

}
