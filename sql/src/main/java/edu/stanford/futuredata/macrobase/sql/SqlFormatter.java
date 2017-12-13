/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.stanford.futuredata.macrobase.sql;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.getOnlyElement;
import static edu.stanford.futuredata.macrobase.sql.ExpressionFormatter.formatExpression;
import static edu.stanford.futuredata.macrobase.sql.ExpressionFormatter.formatGroupBy;
import static edu.stanford.futuredata.macrobase.sql.ExpressionFormatter.formatOrderBy;
import static edu.stanford.futuredata.macrobase.sql.ExpressionFormatter.formatStringLiteral;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import edu.stanford.futuredata.macrobase.sql.tree.AddColumn;
import edu.stanford.futuredata.macrobase.sql.tree.AliasedRelation;
import edu.stanford.futuredata.macrobase.sql.tree.AllColumns;
import edu.stanford.futuredata.macrobase.sql.tree.AstVisitor;
import edu.stanford.futuredata.macrobase.sql.tree.ColumnDefinition;
import edu.stanford.futuredata.macrobase.sql.tree.CreateTable;
import edu.stanford.futuredata.macrobase.sql.tree.CreateTableAsSelect;
import edu.stanford.futuredata.macrobase.sql.tree.Delete;
import edu.stanford.futuredata.macrobase.sql.tree.DropColumn;
import edu.stanford.futuredata.macrobase.sql.tree.DropTable;
import edu.stanford.futuredata.macrobase.sql.tree.Except;
import edu.stanford.futuredata.macrobase.sql.tree.Execute;
import edu.stanford.futuredata.macrobase.sql.tree.Expression;
import edu.stanford.futuredata.macrobase.sql.tree.Identifier;
import edu.stanford.futuredata.macrobase.sql.tree.Insert;
import edu.stanford.futuredata.macrobase.sql.tree.Intersect;
import edu.stanford.futuredata.macrobase.sql.tree.Join;
import edu.stanford.futuredata.macrobase.sql.tree.JoinCriteria;
import edu.stanford.futuredata.macrobase.sql.tree.JoinOn;
import edu.stanford.futuredata.macrobase.sql.tree.JoinUsing;
import edu.stanford.futuredata.macrobase.sql.tree.Lateral;
import edu.stanford.futuredata.macrobase.sql.tree.LikeClause;
import edu.stanford.futuredata.macrobase.sql.tree.NaturalJoin;
import edu.stanford.futuredata.macrobase.sql.tree.Node;
import edu.stanford.futuredata.macrobase.sql.tree.OrderBy;
import edu.stanford.futuredata.macrobase.sql.tree.Property;
import edu.stanford.futuredata.macrobase.sql.tree.QualifiedName;
import edu.stanford.futuredata.macrobase.sql.tree.Query;
import edu.stanford.futuredata.macrobase.sql.tree.QuerySpecification;
import edu.stanford.futuredata.macrobase.sql.tree.Relation;
import edu.stanford.futuredata.macrobase.sql.tree.RenameColumn;
import edu.stanford.futuredata.macrobase.sql.tree.RenameSchema;
import edu.stanford.futuredata.macrobase.sql.tree.RenameTable;
import edu.stanford.futuredata.macrobase.sql.tree.Row;
import edu.stanford.futuredata.macrobase.sql.tree.SampledRelation;
import edu.stanford.futuredata.macrobase.sql.tree.Select;
import edu.stanford.futuredata.macrobase.sql.tree.SelectItem;
import edu.stanford.futuredata.macrobase.sql.tree.ShowColumns;
import edu.stanford.futuredata.macrobase.sql.tree.ShowTables;
import edu.stanford.futuredata.macrobase.sql.tree.SingleColumn;
import edu.stanford.futuredata.macrobase.sql.tree.Table;
import edu.stanford.futuredata.macrobase.sql.tree.TableSubquery;
import edu.stanford.futuredata.macrobase.sql.tree.Union;
import edu.stanford.futuredata.macrobase.sql.tree.Unnest;
import edu.stanford.futuredata.macrobase.sql.tree.Values;
import edu.stanford.futuredata.macrobase.sql.tree.With;
import edu.stanford.futuredata.macrobase.sql.tree.WithQuery;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class SqlFormatter {

  private static final String INDENT = "   ";
  private static final Pattern NAME_PATTERN = Pattern.compile("[a-z_][a-z0-9_]*");

  private SqlFormatter() {
  }

  static String formatSql(Node root, Optional<List<Expression>> parameters) {
    StringBuilder builder = new StringBuilder();
    new Formatter(builder, parameters).process(root, 0);
    return builder.toString();
  }

  private static class Formatter
      extends AstVisitor<Void, Integer> {

    private final StringBuilder builder;
    private final Optional<List<Expression>> parameters;

    Formatter(StringBuilder builder, Optional<List<Expression>> parameters) {
      this.builder = builder;
      this.parameters = parameters;
    }

    @Override
    protected Void visitNode(Node node, Integer indent) {
      throw new UnsupportedOperationException("not yet implemented: " + node);
    }

    @Override
    protected Void visitExpression(Expression node, Integer indent) {
      checkArgument(indent == 0, "visitExpression should only be called at root");
      builder.append(formatExpression(node, parameters));
      return null;
    }

    @Override
    protected Void visitUnnest(Unnest node, Integer indent) {
      builder.append("UNNEST(")
          .append(node.getExpressions().stream()
              .map(expression -> formatExpression(expression, parameters))
              .collect(joining(", ")))
          .append(")");
      if (node.isWithOrdinality()) {
        builder.append(" WITH ORDINALITY");
      }
      return null;
    }

    @Override
    protected Void visitLateral(Lateral node, Integer indent) {
      append(indent, "LATERAL (");
      process(node.getQuery(), indent + 1);
      append(indent, ")");
      return null;
    }

    @Override
    protected Void visitExecute(Execute node, Integer indent) {
      append(indent, "EXECUTE ");
      builder.append(node.getName());
      List<Expression> parameters = node.getParameters();
      if (!parameters.isEmpty()) {
        builder.append(" USING ");
        Joiner.on(", ").appendTo(builder, parameters);
      }
      return null;
    }

    @Override
    protected Void visitQuery(Query node, Integer indent) {
      if (node.getWith().isPresent()) {
        With with = node.getWith().get();
        append(indent, "WITH");
        if (with.isRecursive()) {
          builder.append(" RECURSIVE");
        }
        builder.append("\n  ");
        Iterator<WithQuery> queries = with.getQueries().iterator();
        while (queries.hasNext()) {
          WithQuery query = queries.next();
          append(indent, formatExpression(query.getName(), parameters));
          query.getColumnNames().ifPresent(columnNames -> appendAliasColumns(builder, columnNames));
          builder.append(" AS ");
          process(new TableSubquery(query.getQuery()), indent);
          builder.append('\n');
          if (queries.hasNext()) {
            builder.append(", ");
          }
        }
      }

      processRelation(node.getQueryBody(), indent);

      if (node.getOrderBy().isPresent()) {
        process(node.getOrderBy().get(), indent);
      }

      if (node.getLimit().isPresent()) {
        append(indent, "LIMIT " + node.getLimit().get())
            .append('\n');
      }

      return null;
    }

    @Override
    protected Void visitQuerySpecification(QuerySpecification node, Integer indent) {
      process(node.getSelect(), indent);

      if (node.getFrom().isPresent()) {
        append(indent, "FROM");
        builder.append('\n');
        append(indent, "  ");
        process(node.getFrom().get(), indent);
      }

      builder.append('\n');

      if (node.getWhere().isPresent()) {
        append(indent, "WHERE " + formatExpression(node.getWhere().get(), parameters))
            .append('\n');
      }

      if (node.getGroupBy().isPresent()) {
        append(indent, "GROUP BY " + (node.getGroupBy().get().isDistinct() ? " DISTINCT " : "")
            + formatGroupBy(node.getGroupBy().get().getGroupingElements())).append('\n');
      }

      if (node.getHaving().isPresent()) {
        append(indent, "HAVING " + formatExpression(node.getHaving().get(), parameters))
            .append('\n');
      }

      if (node.getOrderBy().isPresent()) {
        process(node.getOrderBy().get(), indent);
      }

      if (node.getLimit().isPresent()) {
        append(indent, "LIMIT " + node.getLimit().get())
            .append('\n');
      }
      return null;
    }

    @Override
    protected Void visitOrderBy(OrderBy node, Integer indent) {
      append(indent, formatOrderBy(node, parameters))
          .append('\n');
      return null;
    }

    @Override
    protected Void visitSelect(Select node, Integer indent) {
      append(indent, "SELECT");
      if (node.isDistinct()) {
        builder.append(" DISTINCT");
      }

      if (node.getSelectItems().size() > 1) {
        boolean first = true;
        for (SelectItem item : node.getSelectItems()) {
          builder.append("\n")
              .append(indentString(indent))
              .append(first ? "  " : ", ");

          process(item, indent);
          first = false;
        }
      } else {
        builder.append(' ');
        process(getOnlyElement(node.getSelectItems()), indent);
      }

      builder.append('\n');

      return null;
    }

    @Override
    protected Void visitSingleColumn(SingleColumn node, Integer indent) {
      builder.append(formatExpression(node.getExpression(), parameters));
      if (node.getAlias().isPresent()) {
        builder.append(' ')
            .append(formatExpression(node.getAlias().get(), parameters));
      }

      return null;
    }

    @Override
    protected Void visitAllColumns(AllColumns node, Integer context) {
      builder.append(node.toString());

      return null;
    }

    @Override
    protected Void visitTable(Table node, Integer indent) {
      builder.append(formatName(node.getName()));

      return null;
    }

    @Override
    protected Void visitJoin(Join node, Integer indent) {
      JoinCriteria criteria = node.getCriteria().orElse(null);
      String type = node.getType().toString();
      if (criteria instanceof NaturalJoin) {
        type = "NATURAL " + type;
      }

      if (node.getType() != Join.Type.IMPLICIT) {
        builder.append('(');
      }
      process(node.getLeft(), indent);

      builder.append('\n');
      if (node.getType() == Join.Type.IMPLICIT) {
        append(indent, ", ");
      } else {
        append(indent, type).append(" JOIN ");
      }

      process(node.getRight(), indent);

      if (node.getType() != Join.Type.CROSS && node.getType() != Join.Type.IMPLICIT) {
        if (criteria instanceof JoinUsing) {
          JoinUsing using = (JoinUsing) criteria;
          builder.append(" USING (")
              .append(Joiner.on(", ").join(using.getColumns()))
              .append(")");
        } else if (criteria instanceof JoinOn) {
          JoinOn on = (JoinOn) criteria;
          builder.append(" ON ")
              .append(formatExpression(on.getExpression(), parameters));
        } else if (!(criteria instanceof NaturalJoin)) {
          throw new UnsupportedOperationException("unknown join criteria: " + criteria);
        }
      }

      if (node.getType() != Join.Type.IMPLICIT) {
        builder.append(")");
      }

      return null;
    }

    @Override
    protected Void visitAliasedRelation(AliasedRelation node, Integer indent) {
      process(node.getRelation(), indent);

      builder.append(' ')
          .append(formatExpression(node.getAlias(), parameters));
      appendAliasColumns(builder, node.getColumnNames());

      return null;
    }

    @Override
    protected Void visitSampledRelation(SampledRelation node, Integer indent) {
      process(node.getRelation(), indent);

      builder.append(" TABLESAMPLE ")
          .append(node.getType())
          .append(" (")
          .append(node.getSamplePercentage())
          .append(')');

      return null;
    }

    @Override
    protected Void visitValues(Values node, Integer indent) {
      builder.append(" VALUES ");

      boolean first = true;
      for (Expression row : node.getRows()) {
        builder.append("\n")
            .append(indentString(indent))
            .append(first ? "  " : ", ");

        builder.append(formatExpression(row, parameters));
        first = false;
      }
      builder.append('\n');

      return null;
    }

    @Override
    protected Void visitTableSubquery(TableSubquery node, Integer indent) {
      builder.append('(')
          .append('\n');

      process(node.getQuery(), indent + 1);

      append(indent, ") ");

      return null;
    }

    @Override
    protected Void visitUnion(Union node, Integer indent) {
      Iterator<Relation> relations = node.getRelations().iterator();

      while (relations.hasNext()) {
        processRelation(relations.next(), indent);

        if (relations.hasNext()) {
          builder.append("UNION ");
          if (!node.isDistinct()) {
            builder.append("ALL ");
          }
        }
      }

      return null;
    }

    @Override
    protected Void visitExcept(Except node, Integer indent) {
      processRelation(node.getLeft(), indent);

      builder.append("EXCEPT ");
      if (!node.isDistinct()) {
        builder.append("ALL ");
      }

      processRelation(node.getRight(), indent);

      return null;
    }

    @Override
    protected Void visitIntersect(Intersect node, Integer indent) {
      Iterator<Relation> relations = node.getRelations().iterator();

      while (relations.hasNext()) {
        processRelation(relations.next(), indent);

        if (relations.hasNext()) {
          builder.append("INTERSECT ");
          if (!node.isDistinct()) {
            builder.append("ALL ");
          }
        }
      }

      return null;
    }

    @Override
    protected Void visitShowTables(ShowTables node, Integer context) {
      builder.append("SHOW TABLES");

      node.getSchema().ifPresent(value ->
          builder.append(" FROM ")
              .append(formatName(value)));

      node.getLikePattern().ifPresent(value ->
          builder.append(" LIKE ")
              .append(formatStringLiteral(value)));

      return null;
    }

    @Override
    protected Void visitShowColumns(ShowColumns node, Integer context) {
      builder.append("SHOW COLUMNS FROM ")
          .append(formatName(node.getTable()));

      return null;
    }

    @Override
    protected Void visitDelete(Delete node, Integer context) {
      builder.append("DELETE FROM ")
          .append(formatName(node.getTable().getName()));

      if (node.getWhere().isPresent()) {
        builder.append(" WHERE ")
            .append(formatExpression(node.getWhere().get(), parameters));
      }

      return null;
    }

    @Override
    protected Void visitRenameSchema(RenameSchema node, Integer context) {
      builder.append("ALTER SCHEMA ")
          .append(formatName(node.getSource()))
          .append(" RENAME TO ")
          .append(formatExpression(node.getTarget(), parameters));

      return null;
    }

    @Override
    protected Void visitCreateTableAsSelect(CreateTableAsSelect node, Integer indent) {
      builder.append("CREATE TABLE ");
      if (node.isNotExists()) {
        builder.append("IF NOT EXISTS ");
      }
      builder.append(formatName(node.getName()));

      if (node.getColumnAliases().isPresent()) {
        String columnList = node.getColumnAliases().get().stream()
            .map(element -> formatExpression(element, parameters)).collect(joining(", "));
        builder.append(format("( %s )", columnList));
      }

      if (node.getComment().isPresent()) {
        builder.append("\nCOMMENT " + formatStringLiteral(node.getComment().get()));
      }

      builder.append(formatProperties(node.getProperties()));

      builder.append(" AS ");
      process(node.getQuery(), indent);

      if (!node.isWithData()) {
        builder.append(" WITH NO DATA");
      }

      return null;
    }

    @Override
    protected Void visitCreateTable(CreateTable node, Integer indent) {
      builder.append("CREATE TABLE ");
      if (node.isNotExists()) {
        builder.append("IF NOT EXISTS ");
      }
      String tableName = formatName(node.getName());
      builder.append(tableName).append(" (\n");

      String elementIndent = indentString(indent + 1);
      String columnList = node.getElements().stream()
          .map(element -> {
            if (element instanceof ColumnDefinition) {
              ColumnDefinition column = (ColumnDefinition) element;
              return elementIndent + formatExpression(column.getName(), parameters) + " " + column
                  .getType() +
                  column.getComment()
                      .map(comment -> " COMMENT " + formatStringLiteral(comment))
                      .orElse("");
            }
            if (element instanceof LikeClause) {
              LikeClause likeClause = (LikeClause) element;
              StringBuilder builder = new StringBuilder(elementIndent);
              builder.append("LIKE ")
                  .append(formatName(likeClause.getTableName()));
              if (likeClause.getPropertiesOption().isPresent()) {
                builder.append(" ")
                    .append(likeClause.getPropertiesOption().get().name())
                    .append(" PROPERTIES");
              }
              return builder.toString();
            }
            throw new UnsupportedOperationException("unknown table element: " + element);
          })
          .collect(joining(",\n"));
      builder.append(columnList);
      builder.append("\n").append(")");

      if (node.getComment().isPresent()) {
        builder.append("\nCOMMENT " + formatStringLiteral(node.getComment().get()));
      }

      builder.append(formatProperties(node.getProperties()));

      return null;
    }

    private String formatProperties(List<Property> properties) {
      if (properties.isEmpty()) {
        return "";
      }
      String propertyList = properties.stream()
          .map(element -> INDENT +
              formatExpression(element.getName(), parameters) + " = " +
              formatExpression(element.getValue(), parameters))
          .collect(joining(",\n"));

      return "\nWITH (\n" + propertyList + "\n)";
    }

    private static String formatName(String name) {
      if (NAME_PATTERN.matcher(name).matches()) {
        return name;
      }
      return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String formatName(QualifiedName name) {
      return name.getOriginalParts().stream()
          .map(Formatter::formatName)
          .collect(joining("."));
    }

    @Override
    protected Void visitDropTable(DropTable node, Integer context) {
      builder.append("DROP TABLE ");
      if (node.isExists()) {
        builder.append("IF EXISTS ");
      }
      builder.append(node.getTableName());

      return null;
    }

    @Override
    protected Void visitRenameTable(RenameTable node, Integer context) {
      builder.append("ALTER TABLE ")
          .append(node.getSource())
          .append(" RENAME TO ")
          .append(node.getTarget());

      return null;
    }

    @Override
    protected Void visitRenameColumn(RenameColumn node, Integer context) {
      builder.append("ALTER TABLE ")
          .append(node.getTable())
          .append(" RENAME COLUMN ")
          .append(node.getSource())
          .append(" TO ")
          .append(node.getTarget());

      return null;
    }

    @Override
    protected Void visitDropColumn(DropColumn node, Integer context) {
      builder.append("ALTER TABLE ")
          .append(formatName(node.getTable()))
          .append(" DROP COLUMN ")
          .append(formatExpression(node.getColumn(), parameters));

      return null;
    }

    @Override
    protected Void visitAddColumn(AddColumn node, Integer indent) {
      builder.append("ALTER TABLE ")
          .append(node.getName())
          .append(" ADD COLUMN ")
          .append(node.getColumn().getName())
          .append(" ")
          .append(node.getColumn().getType());

      return null;
    }

    @Override
    protected Void visitInsert(Insert node, Integer indent) {
      builder.append("INSERT INTO ")
          .append(node.getTarget())
          .append(" ");

      if (node.getColumns().isPresent()) {
        builder.append("(")
            .append(Joiner.on(", ").join(node.getColumns().get()))
            .append(") ");
      }

      process(node.getQuery(), indent);

      return null;
    }

    @Override
    protected Void visitRow(Row node, Integer indent) {
      builder.append("ROW(");
      boolean firstItem = true;
      for (Expression item : node.getItems()) {
        if (!firstItem) {
          builder.append(", ");
        }
        process(item, indent);
        firstItem = false;
      }
      builder.append(")");
      return null;
    }

    private void processRelation(Relation relation, Integer indent) {
      // TODO: handle this properly
      if (relation instanceof Table) {
        builder.append("TABLE ")
            .append(((Table) relation).getName())
            .append('\n');
      } else {
        process(relation, indent);
      }
    }

    private StringBuilder append(int indent, String value) {
      return builder.append(indentString(indent))
          .append(value);
    }

    private static String indentString(int indent) {
      return Strings.repeat(INDENT, indent);
    }
  }

  private static void appendAliasColumns(StringBuilder builder, List<Identifier> columns) {
    if ((columns != null) && (!columns.isEmpty())) {
      String formattedColumns = columns.stream()
          .map(name -> formatExpression(name, Optional.empty()))
          .collect(Collectors.joining(", "));

      builder.append(" (")
          .append(formattedColumns)
          .append(')');
    }
  }
}
