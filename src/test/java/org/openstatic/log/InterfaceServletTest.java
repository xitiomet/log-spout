package org.openstatic.log;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.junit.Test;

public class InterfaceServletTest
{
    @Test
    public void servesPackagedPageAndAssets() throws Exception
    {
        Server server = new Server(0);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(InterfaceServlet.class, "/*");
        server.setHandler(context);
        try
        {
            server.start();
            int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
            HttpClient client = HttpClient.newHttpClient();
            String[][] assets = {
                {"/", "text/html"}, {"/index.html", "text/html"},
                {"/index.js", "text/javascript"}, {"/favicon.ico", "image/x-icon"},
                {"/icon-32.png", "image/png"}, {"/led-green.svg", "image/svg+xml"},
                {"/node_modules/xterm/css/xterm.css", "text/css"},
                {"/node_modules/xterm/lib/xterm.js", "text/javascript"},
                {"/node_modules/xterm-addon-fit/lib/xterm-addon-fit.js", "text/javascript"}
            };
            for (String[] asset : assets)
            {
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + asset[0])).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(asset[0], 200, response.statusCode());
                assertTrue(asset[0], response.body().length > 0);
                assertTrue(asset[0], response.headers().firstValue("Content-Type").orElse("")
                    .startsWith(asset[1]));
            }
            HttpResponse<byte[]> missing = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/missing.js")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, missing.statusCode());
        } finally {
            server.stop();
            server.join();
        }
    }
}
