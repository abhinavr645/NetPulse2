package com.netpulse.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** Checks that Maven packages the view and its controller/stylesheet references. */
class DashboardResourcesTest {

    @Test
    void dashboardReferencesThePackagedControllerAndStylesheet() throws Exception {
        URL view = NetPulseApplication.class.getResource("/fxml/FxDashboardView.fxml");
        assertNotNull(view, "Maven must package the main FXML resource");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        try (InputStream input = view.openStream()) {
            Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
            String controller = root.getAttributeNS("http://javafx.com/fxml/1", "controller");
            assertEquals(FxDashboardController.class.getName(), controller);
            assertEquals(FxDashboardController.class, Class.forName(controller));

            String stylesheet = root.getAttribute("stylesheets");
            assertEquals("@../css/styles.css", stylesheet);
            URL expected = NetPulseApplication.class.getResource("/css/styles.css");
            assertNotNull(expected, "Maven must package the stylesheet");
            assertEquals(expected, view.toURI().resolve(stylesheet.substring(1)).toURL());
        }
    }
}
