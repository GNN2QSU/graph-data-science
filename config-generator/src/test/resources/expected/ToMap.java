/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package positive;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import javax.annotation.processing.Generated;

import org.jetbrains.annotations.NotNull;
import org.neo4j.graphalgo.core.CypherMapWrapper;

@Generated("org.neo4j.graphalgo.proc.ConfigurationProcessor")
public final class ToMapConfig implements ToMap {

    private int foo;

    private long bar;

    private double baz;

    public ToMapConfig(int foo, @NotNull CypherMapWrapper config) {
        ArrayList<IllegalArgumentException> errors = new ArrayList<>();
        try {
            this.foo = foo;
        } catch (IllegalArgumentException e) {
            errors.add(e);
        }
        try {
            this.bar = config.requireLong("bar");
        } catch (IllegalArgumentException e) {
            errors.add(e);
        }
        try {
            this.baz = config.requireDouble("baz");
        } catch (IllegalArgumentException e) {
            errors.add(e);
        }
        if (!errors.isEmpty()) {
            if (errors.size() == 1) {
                throw errors.get(0);
            } else {
                String combinedErrorMsg = errors
                    .stream()
                    .map(IllegalArgumentException::getMessage)
                    .reduce(
                        "Multiple errors in configuration arguments:",
                        (combined, msg) -> combined + System.lineSeparator() + "\t\t\t\t" + msg
                    );
                throw new IllegalArgumentException(combinedErrorMsg);
            }
        }
    }

    @Override
    public int foo() {
        return this.foo;
    }

    @Override
    public long bar() {
        return this.bar;
    }

    @Override
    public double baz() {
        return this.baz;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bar", bar());
        map.put("baz", positive.ToMap.add42(baz()));
        return map;
    }
}
