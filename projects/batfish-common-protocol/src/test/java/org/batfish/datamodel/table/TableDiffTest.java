package org.batfish.datamodel.table;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import org.batfish.datamodel.answers.Schema;
import org.batfish.datamodel.questions.DisplayHints;
import org.batfish.datamodel.table.Row.RowBuilder;
import org.junit.Test;

public class TableDiffTest {

  private static List<ColumnMetadata> mixColumns() {
    return ImmutableList.of(
        new ColumnMetadata("key", Schema.STRING, "key", true, false),
        new ColumnMetadata("both", Schema.STRING, "both", true, true),
        new ColumnMetadata("value", Schema.STRING, "value", false, true),
        new ColumnMetadata("neither", Schema.STRING, "neither", false, false));
  }

  private static Row mixRow(String key, String both, String value, String neither) {
    return Row.of("key", key, "both", both, "value", value, "neither", neither);
  }

  @Test
  public void diffCells() {
    /** One value is null */
    assertThat(
        TableDiff.diffCells(null, new Integer(1), Schema.INTEGER),
        equalTo(TableDiff.resultDifferent(TableDiff.NULL_VALUE_BASE)));

    /** Both values are null */
    assertThat(TableDiff.diffCells(null, null, Schema.INTEGER), equalTo(TableDiff.RESULT_SAME));

    /** Integer difference */
    assertThat(
        TableDiff.diffCells(new Integer(1), new Integer(2), Schema.INTEGER),
        equalTo(TableDiff.resultDifferent("-1")));

    /** Set difference */
    String diffValue =
        TableDiff.diffCells(
            ImmutableSet.of(1, 2), ImmutableSet.of(2, 3), Schema.set(Schema.INTEGER));
    assertThat(diffValue, containsString("+ [1]"));
    assertThat(diffValue, containsString("- [3]"));

    /** String (and other types) */
    assertThat(TableDiff.diffCells("a", "b", Schema.STRING), equalTo(TableDiff.RESULT_DIFFERENT));
    assertThat(TableDiff.diffCells("a", "a", Schema.STRING), equalTo(TableDiff.RESULT_SAME));
  }

  @Test
  public void diffMetadata() {
    List<ColumnMetadata> inputColumns = mixColumns();

    /**
     * "key" and "both" should be present as keys; three versions of "value" should be present; and
     * two versions of "neither" should be present
     */
    TableMetadata expectedMetadata =
        new TableMetadata(
            ImmutableList.of(
                new ColumnMetadata("key", Schema.STRING, "key", true, false),
                new ColumnMetadata("both", Schema.STRING, "both", true, false),
                new ColumnMetadata(
                    TableDiff.diffColumnName("value"),
                    Schema.STRING,
                    TableDiff.diffColumnDescription("value"),
                    false,
                    true),
                new ColumnMetadata(
                    TableDiff.baseColumnName("value"), Schema.STRING, "value", false, false),
                new ColumnMetadata(
                    TableDiff.deltaColumnName("value"), Schema.STRING, "value", false, false),
                new ColumnMetadata(
                    TableDiff.baseColumnName("neither"), Schema.STRING, "neither", false, false),
                new ColumnMetadata(
                    TableDiff.deltaColumnName("neither"), Schema.STRING, "neither", false, false)),
            new DisplayHints(null, null, "[key, both]"));

    assertThat(
        TableDiff.diffMetadata(new TableMetadata(inputColumns, null)), equalTo(expectedMetadata));
  }

  @Test
  public void diffRowValues() {
    Row baseRow = mixRow("key1", "both1", "value1", null);
    Row deltaRow = mixRow(null, null, null, "neither2");

    RowBuilder diff = Row.builder();
    TableDiff.diffRowValues(diff, baseRow, deltaRow, new TableMetadata(mixColumns(), null));
    assertThat(
        diff.build(),
        equalTo(
            Row.of(
                // value
                TableDiff.diffColumnName("value"),
                TableDiff.diffCells("value1", null, Schema.STRING),
                TableDiff.baseColumnName("value"),
                "value1",
                TableDiff.deltaColumnName("value"),
                null,
                // neither
                TableDiff.baseColumnName("neither"),
                null,
                TableDiff.deltaColumnName("neither"),
                "neither2")));
  }

  /** One of the rows is null */
  @Test
  public void diffRowValuesNull() {
    Row row = Row.of("key", "value");
    TableMetadata metadata =
        new TableMetadata(
            ImmutableList.of(new ColumnMetadata("key", Schema.STRING, "desc", false, true)), null);

    // delta is null
    RowBuilder diff1 = Row.builder();
    TableDiff.diffRowValues(diff1, row, null, metadata);
    assertThat(
        diff1.build(),
        equalTo(
            Row.of(
                TableDiff.diffColumnName("key"),
                TableDiff.resultDifferent(TableDiff.MISSING_KEY_DELTA),
                TableDiff.baseColumnName("key"),
                "value",
                TableDiff.deltaColumnName("key"),
                null)));

    // base is null
    RowBuilder diff2 = Row.builder();
    TableDiff.diffRowValues(diff2, null, row, metadata);
    assertThat(
        diff2.build(),
        equalTo(
            Row.of(
                TableDiff.diffColumnName("key"),
                TableDiff.resultDifferent(TableDiff.MISSING_KEY_BASE),
                TableDiff.baseColumnName("key"),
                null,
                TableDiff.deltaColumnName("key"),
                "value")));
  }

  /**
   * Checks if we get back the expected number of rows. Multiple rows with same key within a table
   * should be thrown out, and the same value across two tables should not be reported.
   */
  @Test
  public void diffTablesTestRowCount() {
    List<ColumnMetadata> columns =
        ImmutableList.of(
            new ColumnMetadata("key", Schema.STRING, "key", true, false),
            new ColumnMetadata("value", Schema.STRING, "value", false, true));
    TableMetadata metadata = new TableMetadata(columns, null);

    Row row1 = Row.of("key", "sameKey", "value", "value1");
    Row row2 = Row.of("key", "sameKey", "value", "value2");
    Row row3 = Row.of("key", "diffKey", "value", "value3");

    TableAnswerElement table0 = new TableAnswerElement(metadata);
    TableAnswerElement table123 =
        new TableAnswerElement(metadata).addRow(row1).addRow(row2).addRow(row3);

    // expected count is 2: Rows with identical keys (1,2) are compressed
    assertThat(TableDiff.diffTables(table123, table0).getRows().size(), equalTo(2));
    assertThat(TableDiff.diffTables(table0, table123).getRows().size(), equalTo(2));

    TableAnswerElement table13 = new TableAnswerElement(metadata).addRow(row1).addRow(row3);
    TableAnswerElement table2 = new TableAnswerElement(metadata).addRow(row2);

    // expected count is 2: one for diffKey, one for sameKey
    assertThat(TableDiff.diffTables(table13, table2).getRows().size(), equalTo(2));
    assertThat(TableDiff.diffTables(table2, table13).getRows().size(), equalTo(2));

    TableAnswerElement table1 = new TableAnswerElement(metadata).addRow(row1);

    // expected count is 1: one for diffKey; sameKey should be removed since values are same
    assertThat(TableDiff.diffTables(table1, table13).getRows().size(), equalTo(1));
    assertThat(TableDiff.diffTables(table13, table1).getRows().size(), equalTo(1));
  }

  @Test
  public void diffTablesTestRowComposition() {
    List<ColumnMetadata> columns = mixColumns();
    TableMetadata metadata = new TableMetadata(columns, null);

    Row row1 = mixRow("key1", "both1", "value1", "neither1");
    Row row2 = mixRow("key1", "both1", "value2", "neither2");

    TableAnswerElement table1 = new TableAnswerElement(metadata).addRow(row1);
    TableAnswerElement table2 = new TableAnswerElement(metadata).addRow(row2);

    Rows expectedRows =
        new Rows()
            .add(
                Row.builder()
                    .put("key", "key1")
                    .put("both", "both1")
                    .put(TableDiff.diffColumnName("value"), TableDiff.RESULT_DIFFERENT)
                    .put(TableDiff.baseColumnName("value"), "value1")
                    .put(TableDiff.deltaColumnName("value"), "value2")
                    .put(TableDiff.baseColumnName("neither"), "neither1")
                    .put(TableDiff.deltaColumnName("neither"), "neither2")
                    .build());

    assertThat(TableDiff.diffTables(table1, table2).getRows(), equalTo(expectedRows));
  }
}
