package com.aegivault.aegivault.dataset.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the safe metadata model: the discovered types are immutable,
 * they reject nonsense, and — the point that matters most — none of them has a
 * field in which a row value, a sample, or a secret could be carried.
 */
class PostgresSchemaModelTest {

    @Test
    void aColumnRequiresANameAPositiveOrdinalAndAType() {
        assertThatThrownBy(() -> new PostgresColumn(" ", 1, "int4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresColumn("id", 0, "int4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresColumn("id", -1, "int4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresColumn("id", 1, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTableRequiresANameAndCopiesItsColumns() {
        assertThatThrownBy(() -> new PostgresTable(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresTable("customers", null))
                .isInstanceOf(NullPointerException.class);

        List<PostgresColumn> mutable = new ArrayList<>();
        mutable.add(new PostgresColumn("id", 1, "int4"));
        PostgresTable table = new PostgresTable("customers", mutable);
        mutable.add(new PostgresColumn("late", 2, "text"));

        // The caller's list is copied, so later mutation cannot reach the model.
        assertThat(table.columns()).hasSize(1);
        assertThatThrownBy(() -> table.columns().add(new PostgresColumn("x", 1, "int4")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSchemaRequiresANameAndCopiesItsTables() {
        assertThatThrownBy(() -> new PostgresSchema("  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresSchema("public", null))
                .isInstanceOf(NullPointerException.class);

        PostgresTable orders = new PostgresTable("orders", List.of());
        PostgresSchema schema = new PostgresSchema("public", new ArrayList<>(List.of(orders)));

        assertThat(schema.tableCount()).isEqualTo(1);
        assertThat(schema.table("orders")).contains(orders);
        assertThat(schema.table("missing")).isEmpty();
        assertThatThrownBy(() -> schema.table(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptySchemaIsAResultNotAFailure() {
        PostgresSchema schema = new PostgresSchema("public", List.of());

        assertThat(schema.tableCount()).isZero();
        assertThat(schema.tables()).isEmpty();
    }

    @Test
    void theModelHasNowhereToHoldRowDataOrCredentials() {
        // The structural guarantee behind "no production row data is copied":
        // every reachable record component is a name, a count, or a type.
        List<String> components = new ArrayList<>();
        for (Class<?> type : List.of(PostgresSchema.class, PostgresTable.class, PostgresColumn.class)) {
            for (var component : type.getRecordComponents()) {
                components.add(component.getType().getSimpleName() + " " + component.getName());
            }
        }

        assertThat(components).containsExactlyInAnyOrder(
                "String schemaName", "List tables", "String name", "List columns",
                "String name", "int ordinalPosition", "String dataType");
        assertThat(components)
                .noneMatch(component -> component.contains("UUID")
                        || component.contains("row")
                        || component.contains("sample")
                        || component.contains("value")
                        || component.contains("password")
                        || component.contains("credential")
                        || component.contains("content"));
    }
}
