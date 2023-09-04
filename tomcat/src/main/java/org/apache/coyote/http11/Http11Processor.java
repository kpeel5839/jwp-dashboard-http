package org.apache.coyote.http11;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import nextstep.jwp.db.InMemoryUserRepository;
import nextstep.jwp.exception.UncheckedServletException;
import nextstep.jwp.model.User;
import org.apache.coyote.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Http11Processor implements Runnable, Processor {

    private static final Logger log = LoggerFactory.getLogger(Http11Processor.class);
    private final Map<String, Function<HttpRequest, String>> getMethodControllerMapper = Map.of(
            "/", this::loadRootPage,
            "/login", this::loadLoginPage
    );
    private final Map<String, Function<HttpRequest, String>> postMethodControllerMapper = Map.of(
            "/register", this::register
    );
    private final Map<HttpMethod, Map<String, Function<HttpRequest, String>>> controllerMapperByMethod = Map.of(
            HttpMethod.GET, getMethodControllerMapper,
            HttpMethod.POST, postMethodControllerMapper
    );
    private final Socket connection;

    public Http11Processor(final Socket connection) {
        this.connection = connection;
    }

    @Override
    public void run() {
        log.info(
                "connect host: {}, port: {}",
                connection.getInetAddress(),
                connection.getPort()
        );
        process(connection);
    }

    @Override
    public void process(final Socket connection) {
        try (final InputStream inputStream = connection.getInputStream();
             final OutputStream outputStream = connection.getOutputStream()) {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            HttpRequestHeader httpRequestHeader = getHttpRequestHeader(bufferedReader);
            HttpRequestBody httpRequestBody = getHttpRequestBody(httpRequestHeader, bufferedReader);
            HttpRequest request = new HttpRequest(httpRequestHeader, httpRequestBody);
            String response = mapController(request);
            outputStream.write(response.getBytes());
            outputStream.flush();
        } catch (IOException | UncheckedServletException e) {
            log.error(e.getMessage(), e);
        }
    }

    private HttpRequestHeader getHttpRequestHeader(BufferedReader bufferedReader) throws IOException {
        StringBuilder header = new StringBuilder();
        String line;

        do {
            line = bufferedReader.readLine();
            if (line == null) {
                break;
            }
            header.append(line).append("\n");
        } while (!"".equals(line));

        return extractHttpRequest(header);
    }

    private HttpRequestHeader extractHttpRequest(StringBuilder content) {
        String[] lines = content.toString()
                .split("\n");
        String[] methodAndRequestUrl = lines[0].split(" ");

        return HttpRequestHeader.of(
                methodAndRequestUrl[0],
                methodAndRequestUrl[1],
                Arrays.copyOfRange(lines, 1, lines.length)
        );
    }

    private HttpRequestBody getHttpRequestBody(HttpRequestHeader header, BufferedReader bufferedReader) throws IOException {
        if (header.getMethod() == HttpMethod.GET) {
            return HttpRequestBody.from("");
        }

        int contentLength = Integer.parseInt(header.get("Content-Length"));
        char[] buffer = new char[contentLength];
        bufferedReader.read(buffer, 0, contentLength);
        String requestBody = new String(buffer);

        return HttpRequestBody.from(requestBody);
    }

    private String mapController(HttpRequest httpRequest) {
        HttpRequestHeader httpRequestHeader = httpRequest.getHttpRequestHeader();
        HttpMethod method = httpRequestHeader.getMethod();
        String path = httpRequestHeader.getPath();

        return controllerMapperByMethod.getOrDefault(method, getMethodControllerMapper)
                .getOrDefault(path, this::loadExtraPage)
                .apply(httpRequest);
    }

    private String loadExtraPage(HttpRequest httpRequest) {
        HttpRequestHeader httpRequestHeader = httpRequest.getHttpRequestHeader();
        log.info("path {}", httpRequestHeader.getPath());
        String responseBody = readForFilePath(convertAbsoluteUrl(httpRequestHeader));

        return String.join("\r\n",
                "HTTP/1.1 200 OK ",
                "Content-Type: " + httpRequestHeader.getContentType() + ";charset=utf-8 ",
                "Content-Length: " + responseBody.getBytes().length + " ",
                "",
                responseBody);
    }

    private String loadRootPage(HttpRequest httpRequest) {
        HttpRequestHeader httpRequestHeader = httpRequest.getHttpRequestHeader();
        String responseBody = "Hello world!";

        return String.join("\r\n",
                "HTTP/1.1 200 OK ",
                "Content-Type: " + httpRequestHeader.getContentType() + ";charset=utf-8 ",
                "Content-Length: " + responseBody.getBytes().length + " ",
                "",
                responseBody);
    }

    private String loadLoginPage(HttpRequest httpRequest) {
        HttpRequestHeader httpRequestHeader = httpRequest.getHttpRequestHeader();
        Map<String, String> queryStrings = httpRequestHeader.getQueryStrings();
        String account = httpRequestHeader.getQueryString("account");
        String password = httpRequestHeader.getQueryString("password");
        Optional<User> savedUser = InMemoryUserRepository.findByAccount(account);
        int status = 302;
        String redirectionUrl = "401.html";

        if (queryStrings.isEmpty()) {
            status = 200;
        }

        if (savedUser.isPresent() && savedUser.get().checkPassword(password)) {
            redirectionUrl = "index.html";
        }

        String responseBody = readForFilePath(convertAbsoluteUrl(httpRequestHeader));

        return String.join("\r\n",
                "HTTP/1.1 " + status + " OK ",
                "Content-Type: " + httpRequestHeader.getContentType() + ";charset=utf-8 ",
                "Content-Length: " + responseBody.getBytes().length + " ",
                "Location: " + redirectionUrl + " ",
                "",
                responseBody);
    }

    private String register(HttpRequest httpRequest) {
        HttpRequestHeader httpRequestHeader = httpRequest.getHttpRequestHeader();
        HttpRequestBody requestBody = httpRequest.getHttpRequestBody();
        String account = requestBody.get("account");
        String password = requestBody.get("password");
        String email = requestBody.get("email");
        String responseBody = readForFilePath(convertAbsoluteUrl(httpRequestHeader));
        String redirectionUrl = "/index.html";
        User user = new User(account, password, email);
        InMemoryUserRepository.save(user);

        return String.join("\r\n",
                "HTTP/1.1 302 OK ",
                "Content-Type: " + httpRequestHeader.getContentType() + ";charset=utf-8 ",
                "Content-Length: " + responseBody.getBytes().length + " ",
                "Location: " + redirectionUrl + " ",
                "",
                responseBody);
    }

    private URL convertAbsoluteUrl(HttpRequestHeader httpRequestHeader) {
        return getClass().getClassLoader()
                .getResource("static" + httpRequestHeader.getFilePath());
    }

    private String readForFilePath(URL path) {
        try (FileInputStream fileInputStream = new FileInputStream(path.getPath())) {
            return readFile(fileInputStream);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return "";
        }
    }

    private String readFile(FileInputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder fileContent = new StringBuilder();
        while (true) {
            String line = bufferedReader.readLine();
            if (line == null) {
                break;
            }
            fileContent.append(line).append("\n");
        }
        return fileContent.toString();
    }

}
