package com.github.dbmdz.solrocr.formats.alto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** @author u.hartwig */
public class AltoFormatTest {

  /**
   * Prevent regression
   *
   * <p>Highlighting OCR fields=[ocr_text] for query=ocr_text:china in docIDs=[173] with
   * maxPassagesOcr=[100]
   *
   * @throws IOException
   */
  @Test
  public void testHighlightingHallischeOrientWiss01() throws Exception {

    Path path = Paths.get("src/test/resources/data/alto_concatenated_doc_fragment_048.xml");
    String strContents = Files.readString(path);
    assertEquals(3293, strContents.length());

    AltoFormat altoFormat = new AltoFormat();
    RuntimeException rExc =
        assertThrows(
            RuntimeException.class,
            () -> {
              altoFormat.getContainingWordLimits(strContents, 33785);
            });

    String errToken = "Invalid start:2521 - end:-1 for fragment";
    assertTrue(rExc.getMessage().contains(errToken));
  }
}
