package com.github.dbmdz.solrocr.iter;

import com.github.dbmdz.solrocr.reader.SourceReader;
import com.github.dbmdz.solrocr.reader.SourceReader.Section;
import java.io.IOException;

/** A {@link BreakLocator} that splits an XML-like document on a specific opening or closing tag. */
public class TagBreakLocator extends BaseBreakLocator {
  private final String breakTag;

  public TagBreakLocator(SourceReader reader, String tagName) {
    this(reader, tagName, false);
  }

  public TagBreakLocator(SourceReader reader, String tagName, boolean closing) {
    super(reader);
    if (closing) {
      this.breakTag = ("</" + tagName + ">");
    } else {
      this.breakTag = ("<" + tagName);
    }
  }

  @Override
  protected int getFollowing(int offset) throws IOException {
    String overlapHead = null;
    int globalStart = Math.min(offset + 1, this.text.length());
    // Read the source section-wise  to cut down on String allocations and improve the chance of
    // cache hits in the reader
    while (globalStart < this.text.length()) {
      Section section = this.text.getAsciiSection(globalStart);
      String block = section.text;
      int blockStart = globalStart - section.start;

      if (overlapHead != null) {
        // If the previous section ended with a partial tag, we need to check if the overlapHead
        // combined with the new section until the closing tag contains the breakTag
        int firstTagClose = block.indexOf('>');
        int overlapStart = globalStart - overlapHead.length();
        String overlap = overlapHead.concat(block.substring(0, firstTagClose + 1));
        int overlapMatch = overlap.indexOf(breakTag);
        if (overlapMatch >= 0) {
          return overlapStart + overlapMatch;
        }
        blockStart = firstTagClose + 1;
        overlapHead = null;
      }

      // Truncate block to last '>'
      int blockEnd = block.length();
      int lastTagClose = block.lastIndexOf('>');
      if (lastTagClose > 0 && !isAllBlank(block, lastTagClose + 1, blockEnd)) {
        String overlap = block.substring(lastTagClose + 1, blockEnd);
        if (overlap.indexOf('<') >= 0) {
          // Overlap has an incomplete start of a tag, carry over to next iteration
          overlapHead = overlap;
        }
        blockEnd = lastTagClose + 1;
      }

      int idx = block.indexOf(breakTag, blockStart);
      if (idx >= 0 && idx < blockEnd) {
        return section.start + idx;
      }

      globalStart = section.end;
    }
    return this.text.length();
  }

  @Override
  protected int getPreceding(int offset) throws IOException {
    String overlapTail = null;
    int globalEnd = offset;

    // Read the source section-wise  to cut down on String allocations and improve the chance of
    // cache hits in the reader
    while (globalEnd > 0) {
      Section section = this.text.getAsciiSection(globalEnd);
      String block = section.text;
      int blockEnd = globalEnd - section.start;

      if (overlapTail != null) {
        // If the previous section started with a partial tag, we need to check if the overlapTail
        // combined with the new section until the opening tag contains the breakTag
        int lastTagOpen = block.lastIndexOf('<');
        String overlapHead = block.substring(lastTagOpen);
        String overlap = overlapHead.concat(overlapTail);
        int overlapMatch = optimizedLastIndexOf(overlap, breakTag, overlap.length());
        if (overlapMatch >= 0) {
          return section.start + lastTagOpen + overlapMatch;
        }
        blockEnd = lastTagOpen;
        overlapTail = null;
      }

      int blockStart = 0;
      int firstTagOpen = block.indexOf('<');
      if (firstTagOpen > 0 && firstTagOpen < blockEnd && !isAllBlank(block, 0, firstTagOpen)) {
        String overlap = block.substring(blockStart, firstTagOpen);
        if (overlap.indexOf('>') >= 0) {
          overlapTail = overlap;
        }
        blockStart = firstTagOpen;
      }

      int match = optimizedLastIndexOf(block, breakTag, blockEnd);
      if (match >= blockStart) {
        return section.start + match;
      }

      globalEnd = section.start - 1;
    }

    return 0;
  }
}
