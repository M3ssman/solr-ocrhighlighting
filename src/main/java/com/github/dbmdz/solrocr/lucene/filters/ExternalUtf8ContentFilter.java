package com.github.dbmdz.solrocr.lucene.filters;

import com.github.dbmdz.solrocr.model.SourcePointer;
import com.github.dbmdz.solrocr.util.SourceAwareReader;
import com.github.dbmdz.solrocr.util.Utf8;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import org.apache.lucene.analysis.charfilter.BaseCharFilter;

public class ExternalUtf8ContentFilter extends BaseCharFilter implements SourceAwareReader {

  /**
   * The cumulative offset difference between the input (bytes) and the output (chars) at the
   * current position.
   *
   * <pre>
   * current actual byte offset in input = currentOutOffset + cumulative
   * </pre>
   */
  private int cumulative;

  /** The current <strong>char</strong> offset in the full file; */
  private int currentInOffset;

  /** The current <strong>char</strong> offset in the output. */
  private int currentOutOffset;

  /** Source pointer of this reader, used for debugging and error reporting. */
  private final String pointer;

  private boolean nextIsOffset = false;
  private final Queue<SourcePointer.Region> remainingRegions;
  private SourcePointer.Region currentRegion;

  public ExternalUtf8ContentFilter(Reader input, List<SourcePointer.Region> regions, String pointer)
      throws IOException {
    super(input);
    this.pointer = pointer;
    this.currentOutOffset = 0;
    this.currentInOffset = 0;
    this.cumulative = 0;
    this.remainingRegions = new LinkedList<>(regions);
    currentRegion = remainingRegions.remove();
    if (currentRegion.start > 0) {
      this.addOffCorrectMap(currentOutOffset, currentRegion.startOffset);
      this.cumulative += currentRegion.startOffset;
      this.currentInOffset = (int) this.input.skip(currentRegion.start);
    }
  }

  /**
   * Read <tt>len</tt> <tt>char</tt>s into <tt>cbuf</tt>, starting from character index <tt>off</tt>
   * relative to the beginning of <tt>cbuf</tt> and return the number of <tt>char</tt>s read.
   */
  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    if (currentInOffset == currentRegion.end) {
      return -1;
    }

    int numCharsRead = 0;
    while (len - numCharsRead > 0) {
      int charsRemainingInRegion = currentRegion.end - currentInOffset;
      int charsToRead = len - numCharsRead;
      if (charsToRead > charsRemainingInRegion) {
        charsToRead = charsRemainingInRegion;
      }

      int read = this.input.read(cbuf, off, charsToRead);
      if (read < 0) {
        break;
      }
      correctOffsets(cbuf, off, read);
      numCharsRead += read;
      off += read;

      if (currentInOffset == currentRegion.end) {
        if (remainingRegions.isEmpty()) {
          break;
        }
        currentRegion = remainingRegions.remove();

        cumulative = currentRegion.startOffset - currentOutOffset;
        this.addOffCorrectMap(currentOutOffset, cumulative);
        int toSkip = this.currentRegion.start - this.currentInOffset;
        if (toSkip > 0) {
          this.input.skip(this.currentRegion.start - this.currentInOffset);
        }
        this.currentInOffset = currentRegion.start;
      }
    }
    return numCharsRead > 0 ? numCharsRead : -1;
  }

  private void correctOffsets(char[] cbuf, int off, int len) {
    for (int i = off; i < off + len; i++) {
      if (nextIsOffset) {
        this.addOffCorrectMap(currentOutOffset, cumulative);
        nextIsOffset = false;
      }
      currentInOffset += 1;
      currentOutOffset += 1;
      int cp = Character.codePointAt(cbuf, i);
      int increment = Utf8.encodedLength(cp) - 1;
      if (increment > 0) {
        cumulative += increment;
        nextIsOffset = true;
      }
    }
  }

  @Override
  public Optional<String> getSource() {
    return Optional.of(this.pointer);
  }
}
