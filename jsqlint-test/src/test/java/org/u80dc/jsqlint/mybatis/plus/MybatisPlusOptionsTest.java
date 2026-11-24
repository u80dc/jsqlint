package org.u80dc.jsqlint.mybatis.plus;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MybatisPlusOptionsTest {
    @Test void legacyIdAliasesKeepAssignedIdSemanticsAndNoneDoesNotGenerate() {
        Map.of("ID_WORKER", "ASSIGN_ID", "ID_WORKER_STR", "ASSIGN_ID", "UUID", "ASSIGN_UUID", "NONE", "NONE")
                .forEach((legacy, expected) -> assertEquals(expected, MybatisPlusOptions.defaults().withIdType(legacy).idType()));
        assertEquals("ASSIGN_ID", MybatisPlusOptions.defaults().withIdType(" id_worker ").idType());
    }

    @Test void ignoredAndGlobalDefaultStrategiesUseCanonicalRules() {
        var options = MybatisPlusOptions.defaults().withFieldStrategies("ignored", "default");
        assertEquals("ALWAYS", options.insertStrategy());
        assertEquals("NOT_NULL", options.updateStrategy());
        assertEquals("p_", options.withTablePrefix("p_").tablePrefix());
        assertEquals("ALWAYS", options.withSchema("audit").insertStrategy());
    }

    @Test void unknownStrategiesRemainExplicitErrors() {
        assertThrows(IllegalArgumentException.class, () -> MybatisPlusOptions.defaults().withIdType("CUSTOM_GENERATOR"));
        assertThrows(IllegalArgumentException.class, () -> MybatisPlusOptions.defaults().withFieldStrategies("CUSTOM_RULE", "NOT_NULL"));
    }
}
