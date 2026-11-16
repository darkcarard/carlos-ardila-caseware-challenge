package com.caseware.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.fanout.domain.model.EngagementId;
import com.caseware.fanout.domain.model.FirmId;
import com.caseware.fanout.domain.model.SummaryPair;
import com.caseware.fanout.domain.port.SummaryWarmupPublisher;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Hard invariant: no {@code firm_id} or {@code engagement_id} ever crosses to the global plane.
 *
 * <p>This is the structural half of the test. It walks the types the cross-plane port accepts,
 * recursively through record components and generic arguments, and fails if a regional
 * identifier type, or a component merely <em>named</em> like one, can be reached. The runtime
 * half, which checks serialized payloads, lives with the fan-out processor tests.
 */
class ResidencyBoundaryTest {

    private static final Set<Class<?>> REGIONAL_ID_TYPES = Set.of(FirmId.class, EngagementId.class);

    @Test
    void summaryPairHasExactlyTheFiveGlobalFields() {
        assertThat(SummaryPair.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("productId", "market", "fromVersion", "toVersion", "locale");
    }

    @Test
    void summaryPairCannotReachAnyRegionalIdentifier() {
        assertThat(reachableViolations(SummaryPair.class)).isEmpty();
    }

    @Test
    void crossPlanePublisherOnlyAcceptsIdFreeTypes() {
        List<String> violations = new ArrayList<>();
        for (Method method : SummaryWarmupPublisher.class.getDeclaredMethods()) {
            if (method.isSynthetic() || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            for (Type parameter : method.getGenericParameterTypes()) {
                violations.addAll(reachableViolations(parameter));
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void crossPlanePublisherAcceptsNothingButSummaryPairs() {
        for (Method method : SummaryWarmupPublisher.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertThat(method.getGenericParameterTypes())
                    .as("%s must only accept Set<SummaryPair>", method.getName())
                    .allSatisfy(t -> assertThat(t.getTypeName()).isEqualTo("java.util.Set<" + SummaryPair.class.getName() + ">"));
        }
    }

    private static List<String> reachableViolations(Type root) {
        List<String> violations = new ArrayList<>();
        walk(root, root.getTypeName(), new HashSet<>(), violations);
        return violations;
    }

    private static void walk(Type type, String path, Set<Type> seen, List<String> violations) {
        if (!seen.add(type)) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            walk(parameterized.getRawType(), path, seen, violations);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, path + "<" + argument.getTypeName() + ">", seen, violations);
            }
            return;
        }
        if (!(type instanceof Class<?> clazz)) {
            return;
        }
        if (REGIONAL_ID_TYPES.contains(clazz)) {
            violations.add(path + " reaches regional identifier " + clazz.getSimpleName());
            return;
        }
        if (clazz.isRecord()) {
            for (RecordComponent component : clazz.getRecordComponents()) {
                String name = component.getName().toLowerCase(Locale.ROOT);
                if (name.contains("firm") || name.contains("engagement")) {
                    violations.add(path + "." + component.getName() + " is named like a regional identifier");
                }
                walk(component.getGenericType(), path + "." + component.getName(), seen, violations);
            }
        }
    }
}
