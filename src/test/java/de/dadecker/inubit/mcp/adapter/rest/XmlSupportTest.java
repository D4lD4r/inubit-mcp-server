package de.dadecker.inubit.mcp.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.dadecker.inubit.mcp.domain.model.ErrorCode;
import de.dadecker.inubit.mcp.domain.model.ToolErrorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** T028: hostile XML fails safely, without network or file access (research R-4). */
@Timeout(10)
class XmlSupportTest {

    private static final String SECRET_FILE_CONTENT = "TOP-SECRET-FILE-CONTENT";

    @TempDir
    Path tmp;

    private ServerSocket listener;
    private final AtomicInteger connections = new AtomicInteger();
    private Thread acceptor;

    @BeforeEach
    void startListener() throws IOException {
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try (Socket ignored = listener.accept()) {
                    connections.incrementAndGet();
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stopListener() throws Exception {
        listener.close();
        acceptor.join(2000);
    }

    private String localUrl(String path) {
        return "http://127.0.0.1:" + listener.getLocalPort() + path;
    }

    private Path secretFile() throws IOException {
        Path file = tmp.resolve("secret.txt");
        Files.writeString(file, SECRET_FILE_CONTENT);
        return file;
    }

    private static byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = XmlSupportTest.class.getResourceAsStream(
            "/fixtures/v8_1/rest/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static void assertDomRejects(byte[] xml) {
        assertThatThrownBy(() -> XmlSupport.parse(xml))
            .isInstanceOfSatisfying(ToolErrorException.class, e -> {
                assertThat(e.error().code()).isEqualTo(ErrorCode.UNEXPECTED_RESPONSE);
                assertThat(e.error().toString()).doesNotContain(SECRET_FILE_CONTENT);
            });
    }

    private static void assertStaxRejects(byte[] xml) {
        assertThatThrownBy(() -> {
            XMLStreamReader reader = XmlSupport.newStreamReader(new ByteArrayInputStream(xml));
            StringBuilder text = new StringBuilder();
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.CHARACTERS) {
                    text.append(reader.getText());
                }
            }
            assertThat(text.toString()).doesNotContain(SECRET_FILE_CONTENT);
        }).isInstanceOf(XMLStreamException.class);
    }

    @Test
    void externalFileEntityIsNeitherResolvedNorRead() throws IOException {
        byte[] xml = bytes("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \""
            + secretFile().toUri() + "\">]><r>&x;</r>");

        assertDomRejects(xml);
        assertStaxRejects(xml);
    }

    @Test
    void externalEntityOverTheNetworkIsNeverFetched() {
        byte[] xml = bytes("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \""
            + localUrl("/entity") + "\">]><r>&x;</r>");

        assertDomRejects(xml);
        assertStaxRejects(xml);
        assertThat(connections).hasValue(0);
    }

    @Test
    void externalDtdIsNeverFetched() {
        byte[] xml = bytes("<?xml version=\"1.0\"?><!DOCTYPE r SYSTEM \"" + localUrl("/r.dtd")
            + "\"><r/>");

        assertDomRejects(xml);
        assertStaxRejects(xml);
        assertThat(connections).hasValue(0);
    }

    @Test
    void anyDtdDeclarationIsRejected() {
        byte[] xml = bytes("<?xml version=\"1.0\"?><!DOCTYPE r [<!ELEMENT r ANY>]><r/>");

        assertDomRejects(xml);
        assertStaxRejects(xml);
    }

    @Test
    void parameterEntityIsRejected() {
        byte[] xml = bytes("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY % p SYSTEM \""
            + localUrl("/p") + "\"> %p;]><r/>");

        assertDomRejects(xml);
        assertStaxRejects(xml);
        assertThat(connections).hasValue(0);
    }

    @Test
    @Timeout(5)
    void billionLaughsFailsFast() {
        StringBuilder doc = new StringBuilder("<?xml version=\"1.0\"?><!DOCTYPE lolz [")
            .append("<!ENTITY lol \"lol\">");
        for (int i = 1; i <= 9; i++) {
            doc.append("<!ENTITY lol").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                doc.append("&lol").append(i == 1 ? "" : String.valueOf(i - 1)).append(';');
            }
            doc.append("\">");
        }
        doc.append("]><lolz>&lol9;</lolz>");
        byte[] xml = bytes(doc.toString());

        assertDomRejects(xml);
        assertStaxRejects(xml);
    }

    @Test
    void malformedXmlIsAnUnexpectedResponse() {
        assertDomRejects(bytes("<r><unclosed></r>"));
        assertDomRejects(bytes("not xml at all"));
    }

    @Test
    void namespaceAgnosticLookupOnTheModelList() throws IOException {
        Document doc = XmlSupport.parse(fixture("model_models_owner.xml"));

        Element root = doc.getDocumentElement();
        assertThat(XmlSupport.localName(root)).isEqualTo("ModelList");
        List<Element> models = XmlSupport.descendants(root, "Model");
        assertThat(models).hasSize(443);
        Element first = models.get(0);
        assertThat(XmlSupport.attribute(first, "name")).contains("Workflow-0054");
        assertThat(XmlSupport.attribute(first, "type")).contains("technical");
        // ns2:version is matched by its local name
        assertThat(XmlSupport.attribute(first, "version")).contains("head");
        assertThat(XmlSupport.attribute(first, "group")).contains("GRP-06");
        assertThat(XmlSupport.attribute(first, "missing")).isEmpty();
        assertThat(XmlSupport.children(root, "Model")).hasSize(443);
        assertThat(XmlSupport.firstChild(root, "Model")).contains(first);
        assertThat(XmlSupport.firstChild(root, "NoSuchElement")).isEmpty();
    }

    @Test
    void namespaceAgnosticLookupWithADefaultNamespace() throws IOException {
        Document doc = XmlSupport.parse(fixture("system_info.xml"));

        List<Element> infos = XmlSupport.descendants(doc.getDocumentElement(),
            "SystemInformation");
        assertThat(infos).isNotEmpty();
        assertThat(infos).anySatisfy(info -> {
            assertThat(XmlSupport.attribute(info, "name")).contains("Version");
            assertThat(XmlSupport.attribute(info, "value")).contains("8.1.17");
        });
    }

    @Test
    void textIsTrimmedTextContent() {
        Document doc = XmlSupport.parse(bytes("<a xmlns:x=\"urn:x\"><x:b>  hi  </x:b></a>"));

        Element b = XmlSupport.firstChild(doc.getDocumentElement(), "b").orElseThrow();
        assertThat(XmlSupport.text(b)).isEqualTo("hi");
    }

    @Test
    void streamReaderReadsTheModelListByLocalName() throws Exception {
        XMLStreamReader reader = XmlSupport.newStreamReader(
            new ByteArrayInputStream(fixture("model_models_owner.xml")));
        int models = 0;
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamConstants.START_ELEMENT
                && reader.getLocalName().equals("Model")) {
                models++;
            }
        }
        reader.close();

        assertThat(models).isEqualTo(443);
    }

    @Test
    void connectionsCounterWorks() throws IOException, InterruptedException {
        // guards the "no network access" assertions above against a broken listener
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(),
            listener.getLocalPort())) {
            assertThat(socket.isConnected()).isTrue();
        }
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (connections.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(connections).hasValue(1);
    }

    @Test
    void nextTagAlsoRejectsADtd() throws Exception {
        XMLStreamReader reader = XmlSupport.newStreamReader(new ByteArrayInputStream(bytes(
            "<?xml version=\"1.0\"?><!DOCTYPE r [<!ELEMENT r ANY>]><r>t</r>")));

        assertThatThrownBy(reader::nextTag).isInstanceOf(XMLStreamException.class)
            .hasMessageContaining("not allowed");
    }

    @Test
    void nextTagAndGetElementTextWorkOnPlainXml() throws Exception {
        XMLStreamReader reader = XmlSupport.newStreamReader(new ByteArrayInputStream(bytes(
            "<?xml version=\"1.0\"?><!-- c --><a>\n  <b>hi <![CDATA[there]]></b>\n</a>")));

        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThat(reader.getLocalName()).isEqualTo("a");
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.START_ELEMENT);
        assertThat(reader.getElementText()).isEqualTo("hi there");
        assertThat(reader.nextTag()).isEqualTo(XMLStreamConstants.END_ELEMENT);
    }
}
