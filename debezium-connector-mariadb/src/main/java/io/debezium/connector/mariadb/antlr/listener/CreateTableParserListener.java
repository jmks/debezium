/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mariadb.antlr.listener;

import java.util.List;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.tree.ParseTreeListener;

import io.debezium.connector.mariadb.antlr.MariaDbAntlrDdlParser;
import io.debezium.ddl.parser.mariadb.generated.MariaDBParser;
import io.debezium.relational.ColumnEditor;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;

/**
 * Parser listener for CREATE TABLE statements.
 *
 * @author Chris Cranford
 */
public class CreateTableParserListener extends TableCommonParserListener {

    public CreateTableParserListener(MariaDbAntlrDdlParser parser, List<ParseTreeListener> listeners) {
        super(parser, listeners);
    }

    @Override
    public void enterColumnCreateTable(MariaDBParser.ColumnCreateTableContext ctx) {
        TableId tableId = parser.parseQualifiedTableId(ctx.tableName().fullId());
        if (parser.databaseTables().forTable(tableId) == null) {
            tableEditor = parser.databaseTables().editOrCreateTable(tableId);
            super.enterColumnCreateTable(ctx);
        }
    }

    @Override
    public void exitColumnCreateTable(MariaDBParser.ColumnCreateTableContext ctx) {
        parser.runIfNotNull(() -> {
            // Make sure that the table's character set has been set ...
            if (!tableEditor.hasDefaultCharsetName()) {
                tableEditor.setDefaultCharsetName(parser.charsetForTable(tableEditor.tableId()));
            }
            listeners.remove(columnDefinitionListener);
            columnDefinitionListener = null;
            // remove column definition parser listener
            final String defaultCharsetName = tableEditor.create().defaultCharsetName();
            tableEditor.setColumns(tableEditor.columns().stream()
                    .map(
                            column -> {
                                final ColumnEditor columnEditor = column.edit();
                                if (columnEditor.charsetNameOfTable() == null) {
                                    columnEditor.charsetNameOfTable(defaultCharsetName);
                                }
                                return columnEditor;
                            })
                    .map(ColumnEditor::create)
                    .collect(Collectors.toList()));
            parser.databaseTables().overwriteTable(tableEditor.create());
            parser.signalCreateTable(tableEditor.tableId(), ctx);
        }, tableEditor);
        super.exitColumnCreateTable(ctx);
    }

    @Override
    public void exitCopyCreateTable(MariaDBParser.CopyCreateTableContext ctx) {
        TableId tableId = parser.parseQualifiedTableId(ctx.tableName(0).fullId());
        TableId originalTableId = parser.parseQualifiedTableId(ctx.tableName(1).fullId());
        Table original = parser.databaseTables().forTable(originalTableId);
        if (original != null) {
            parser.databaseTables().overwriteTable(tableId, original.columns(), original.primaryKeyColumnNames(), original.defaultCharsetName(), original.attributes());
            parser.signalCreateTable(tableId, ctx);
        }
        super.exitCopyCreateTable(ctx);
    }

    @Override
    public void enterTableOptionCharset(MariaDBParser.TableOptionCharsetContext ctx) {
        parser.runIfNotNull(() -> {
            if (ctx.charsetName() != null) {
                tableEditor.setDefaultCharsetName(parser.withoutQuotes(ctx.charsetName()));
            }
        }, tableEditor);
        super.enterTableOptionCharset(ctx);
    }

    @Override
    public void enterTableOptionComment(MariaDBParser.TableOptionCommentContext ctx) {
        if (!parser.skipComments()) {
            parser.runIfNotNull(() -> {
                if (ctx.COMMENT() != null) {
                    tableEditor.setComment(parser.withoutQuotes(ctx.STRING_LITERAL().getText()));
                }
            }, tableEditor);
        }
        super.enterTableOptionComment(ctx);
    }
}
