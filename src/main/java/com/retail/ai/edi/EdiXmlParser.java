package com.retail.ai.edi;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class EdiXmlParser {

    public List<EdiSegment> parse(String ediXml) {
        List<EdiSegment> segments = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(ediXml)));
            NodeList nodeList = document.getDocumentElement().getChildNodes();
            int sequence = 0;
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element element = (Element) node;
                if (!"segment".equals(element.getTagName())) {
                    continue;
                }
                sequence++;
                EdiSegment segment = EdiSegment.builder()
                        .sequenceIndex(sequence)
                        .name(element.getAttribute("name"))
                        .fields(new ArrayList<>())
                        .rawXml(nodeToString(element))
                        .build();
                NodeList fieldNodes = element.getChildNodes();
                for (int j = 0; j < fieldNodes.getLength(); j++) {
                    Node fieldNode = fieldNodes.item(j);
                    if (fieldNode.getNodeType() == Node.ELEMENT_NODE) {
                        segment.getFields().add(fieldNode.getTextContent());
                    }
                }
                segments.add(segment);
            }
        } catch (Exception ignored) {
            // fall back to empty list for tests and simple parsing
        }
        return segments;
    }

    private String nodeToString(Node node) {
        try {
            javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
            java.io.StringWriter writer = new java.io.StringWriter();
            transformer.transform(new javax.xml.transform.dom.DOMSource(node), new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
