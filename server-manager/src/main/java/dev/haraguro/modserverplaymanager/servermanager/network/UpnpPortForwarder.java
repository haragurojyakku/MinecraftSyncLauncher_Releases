package dev.haraguro.modserverplaymanager.servermanager.network;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Hand-rolled UPnP Internet Gateway Device (IGD) client: SSDP discovery +
 * SOAP AddPortMapping/DeletePortMapping/GetExternalIPAddress calls. JDK-only,
 * no third-party UPnP library — mirrors this project's preference for
 * hand-rolled clients over a dependency for one narrow protocol (see the
 * Microsoft auth chain, the custom Javalin/Gson JsonMapper).
 *
 * Not a general-purpose UPnP stack: only what's needed to map/unmap a TCP
 * port and read the router's external IP. No retries — a single failed
 * discovery/SOAP call is reported back to the caller as empty/exception,
 * matching the rest of the codebase's no-retry convention.
 */
public class UpnpPortForwarder {

    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    // IGD:2 devices also implement WANIPConnection:1, so searching both
    // generations' root device type maximizes the chance of a reply.
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1"
    };

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** A discovered router's WANIPConnection/WANPPPConnection control endpoint. */
    public record Gateway(String controlUrl, String serviceType, String localAddress) {
    }

    /** Empty if no IGD responded within {@code timeout} (UPnP disabled/unsupported router, or nothing on the LAN). */
    public Optional<Gateway> discover(Duration timeout) {
        String location;
        try {
            location = discoverLocation(timeout);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (location == null) {
            return Optional.empty();
        }
        try {
            return describeDevice(location);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String discoverLocation(Duration timeout) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int) timeout.toMillis());
            InetSocketAddress target = new InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT);
            for (String searchTarget : SEARCH_TARGETS) {
                byte[] packetBody = ("M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + searchTarget + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(packetBody, packetBody.length, target));
            }

            long deadlineNanos = System.nanoTime() + timeout.toNanos();
            byte[] buffer = new byte[8192];
            while (System.nanoTime() < deadlineNanos) {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String text = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                String location = extractHeader(text, "LOCATION");
                if (location != null) {
                    return location;
                }
            }
        }
        return null;
    }

    private static String extractHeader(String response, String name) {
        for (String line : response.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private Optional<Gateway> describeDevice(String location) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(location))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Device description request failed: HTTP " + response.statusCode());
        }

        DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document document = builder.parse(new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));

        String urlBase = firstText(document, "URLBase");
        URI base = URI.create(urlBase != null && !urlBase.isBlank() ? urlBase : location);

        NodeList services = document.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            String serviceType = childText(service, "serviceType");
            if (serviceType == null
                    || !(serviceType.contains("WANIPConnection") || serviceType.contains("WANPPPConnection"))) {
                continue;
            }
            String controlUrl = childText(service, "controlURL");
            if (controlUrl == null) {
                continue;
            }
            URI resolvedControlUrl = base.resolve(controlUrl);
            String localAddress = localAddressFor(resolvedControlUrl);
            return Optional.of(new Gateway(resolvedControlUrl.toString(), serviceType, localAddress));
        }
        return Optional.empty();
    }

    /** The LAN address this machine would use to reach the router — becomes NewInternalClient. */
    private static String localAddressFor(URI controlUrl) throws IOException {
        int port = controlUrl.getPort() > 0 ? controlUrl.getPort() : 80;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(controlUrl.getHost()), port);
            return socket.getLocalAddress().getHostAddress();
        }
    }

    private static String firstText(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    private static String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent().trim() : null;
    }

    /** @throws IOException with the router's rejection detail if the mapping request fails */
    public void addMapping(Gateway gateway, int port, String protocol, String description) throws IOException, InterruptedException {
        String body = soapEnvelope(gateway.serviceType(), "AddPortMapping", ""
                + "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>" + protocol + "</NewProtocol>"
                + "<NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + gateway.localAddress() + "</NewInternalClient>"
                + "<NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + escapeXml(description) + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>0</NewLeaseDuration>");
        soapCall(gateway, "AddPortMapping", body);
    }

    public void deleteMapping(Gateway gateway, int port, String protocol) throws IOException, InterruptedException {
        String body = soapEnvelope(gateway.serviceType(), "DeletePortMapping", ""
                + "<NewRemoteHost></NewRemoteHost>"
                + "<NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>" + protocol + "</NewProtocol>");
        soapCall(gateway, "DeletePortMapping", body);
    }

    public Optional<String> getExternalIpAddress(Gateway gateway) {
        try {
            String response = soapCall(gateway, "GetExternalIPAddress",
                    soapEnvelope(gateway.serviceType(), "GetExternalIPAddress", ""));
            int start = response.indexOf("<NewExternalIPAddress>");
            int end = response.indexOf("</NewExternalIPAddress>");
            if (start < 0 || end < 0) {
                return Optional.empty();
            }
            return Optional.of(response.substring(start + "<NewExternalIPAddress>".length(), end).trim());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String soapEnvelope(String serviceType, String action, String argumentsXml) {
        return "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + argumentsXml
                + "</u:" + action + "></s:Body></s:Envelope>";
    }

    private String soapCall(Gateway gateway, String action, String soapBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway.controlUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"" + gateway.serviceType() + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(soapBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException(action + " rejected by router: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
