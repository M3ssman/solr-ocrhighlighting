package de.digitalcollections.solrocr.util;

import com.google.common.collect.ImmutableSet;
import de.digitalcollections.solrocr.iter.FileBytesCharIterator;
import de.digitalcollections.solrocr.iter.IterableCharSequence;
import de.digitalcollections.solrocr.iter.TagBreakIterator;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagBreakIteratorTest {

  private static final Path utf8Path = Paths.get("src/test/resources/data/miniocr.xml");

  private String stripTags(String val) throws IOException {
    HTMLStripCharFilter filter = new HTMLStripCharFilter(new StringReader(val), ImmutableSet.of("em"));
    return IOUtils.toString(filter).replaceAll("\n", "");
  }

  @Test
  void firstNext() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
  }

  @Test
  void next() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    seq.setIndex(8267);
    int start = it.next();
    int end = it.next();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(stripTags(tag)).isEqualTo("der ");
  }

  @Test
  void lastPrevious() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    int end = it.last();
    int start = it.previous();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(tag).contains(">doch<");
  }

  @Test
  void previous() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    int end = 2872135;
    seq.setIndex(end);
    int start = it.previous();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<w");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(1);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(1);
    assertThat(tag).contains(">Wahlanſprache<");
  }

  @Test
  void previousFirst() throws IOException {
    IterableCharSequence seq = new FileBytesCharIterator(utf8Path, StandardCharsets.UTF_8, null);
    TagBreakIterator it = new TagBreakIterator("w");
    it.setText(seq);
    seq.setIndex(293);
    it.previous();
    it.previous();
    it.previous();
    int end = it.previous();
    int start = it.previous();
    String tag = seq.subSequence(start, end).toString();
    assertThat(tag).startsWith("<?xml");
    assertThat(StringUtils.countMatches(tag, "<w")).isEqualTo(0);
    assertThat(StringUtils.countMatches(tag, "</w>")).isEqualTo(0);
  }
}