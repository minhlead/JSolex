/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.champeau.a4j.jsolex.processing.expr.impl;

import me.champeau.a4j.jsolex.processing.sun.Broadcaster;

import java.util.List;
import java.util.Map;

public class MathFunctions extends AbstractFunctionImpl {
    public MathFunctions(Map<Class<?>, Object> context, Broadcaster broadcaster) {
        super(context, broadcaster);
    }
    
    public Object pow(List<Object> arguments) {
        return applyBinary(arguments, "pow", "exponent", Math::pow);
    }

    public Object log(List<Object> arguments) {
        return applyBinary(arguments, "log", "base", (a, b) -> Math.log(a) / Math.log(b));
    }

    public Object exp(List<Object> arguments) {
        return applyUnary(arguments, "exp", Math::exp);
    }

}