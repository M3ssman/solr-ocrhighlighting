package com.github.dbmdz.solrocr.lucene;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import com.github.dbmdz.solrocr.lucene.filters.OcrCharFilter;
import com.github.dbmdz.solrocr.lucene.filters.SanitizingXmlFilter;
import com.github.dbmdz.solrocr.reader.PeekingReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.commons.io.IOUtils;
import org.apache.lucene.analysis.CharFilter;
import org.junit.jupiter.api.Test;
import solrocr.ExternalUtf8ContentFilterFactory;
import solrocr.OcrCharFilterFactory;

public class SanitizingXmlFilterTest {
  private static final WstxInputFactory xmlInputFactory = new WstxInputFactory();

  static {
    xmlInputFactory.getConfig().setInputParsingMode(WstxInputProperties.PARSING_MODE_DOCUMENTS);
    xmlInputFactory.getConfig().doSupportDTDs(false);
  }

  @Test
  public void sanityTest() throws IOException {
    String brokenXml =
        "<b><c>h&el<»?>lo</c></b></a><a><b><c><>ser>vus&lt;<br>>welt <!,!!>dieses xml ist&;ganz schön kap<>utt<? wa<</c>";
    CharFilter filter = new SanitizingXmlFilter(new StringReader(brokenXml), true);
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).matches(this::isWellFormed, "is well formed XML");
    assertThat(filtered)
        .isEqualTo(
            "<b><c>h_el_»?_lo</c></b>    <a><b><c>__ser>vus&lt;    >welt _!,!!_dieses xml ist_;ganz schön kap__utt_? wa_</c></b></a>");
  }

  @Test
  public void testWellformedXMLDoesntChange() throws IOException {
    Path p = Paths.get("src/test/resources/data/alto_nospace.xml");
    String alto = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    CharFilter filter =
        new SanitizingXmlFilter(new InputStreamReader(new FileInputStream(p.toFile())), true);
    String filtered = IOUtils.toString(filter);
    assertThat(filtered).isEqualTo(alto);
  }

  @Test
  public void testAltoPageCrossing() throws IOException {
    Path p = Paths.get("src/test/resources/data/alto.xml");
    String alto = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    CharFilter filter =
        new SanitizingXmlFilter(new StringReader(alto.substring(99762, 100508)), false);
    String filtered = IOUtils.toString(filter);
    assertThat(isWellFormed(filtered)).isTrue();
  }

  @Test
  public void testCharFilterCompatibility() throws IOException {
    Path p = Paths.get("src/test/resources/data/chronicling_america.xml");
    String alto = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    OcrCharFilterFactory ocrFac = new OcrCharFilterFactory(new HashMap<>());
    OcrCharFilter ocrFilter = (OcrCharFilter) ocrFac.create(new StringReader(alto));
    String filtered = IOUtils.toString(ocrFilter);
    assertThat(filtered).isNotEmpty();
  }

  @Test
  public void testConcatenatedHocr() throws IOException {
    String ptr = Paths.get("src/test/resources/data/multicolumn.hocr").toString();
    Reader reader =
        new ExternalUtf8ContentFilterFactory(new HashMap<>()).create(new StringReader(ptr));
    Reader filteredReader = new PeekingReader(new SanitizingXmlFilter(reader, false), 2048, 16384);
    String filtered = IOUtils.toString(filteredReader);
    assertThat(filtered).isNotEmpty();
  }

  boolean isWellFormed(String xml) {
    try {
      XMLStreamReader reader = xmlInputFactory.createXMLStreamReader(new StringReader(xml));
      while (reader.hasNext()) {
        reader.next();
      }
    } catch (XMLStreamException e) {
      return false;
    }
    return true;
  }
}
