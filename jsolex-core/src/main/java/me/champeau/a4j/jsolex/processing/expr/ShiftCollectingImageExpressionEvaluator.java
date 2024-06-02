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
package me.champeau.a4j.jsolex.processing.expr;

import me.champeau.a4j.jsolex.expr.BuiltinFunction;
import me.champeau.a4j.jsolex.processing.params.ProcessParams;
import me.champeau.a4j.jsolex.processing.sun.Broadcaster;
import me.champeau.a4j.jsolex.processing.util.FileBackedImage;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper;
import me.champeau.a4j.jsolex.processing.util.ImageWrapper32;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ShiftCollectingImageExpressionEvaluator extends ImageExpressionEvaluator {

    private final Set<Double> shifts = new TreeSet<>();
    private final Set<Double> autoWavelenghts = new TreeSet<>();
    private final Map<Double, ImageWrapper> cache = new ConcurrentHashMap<>();
    private boolean autoContinuum = false;

    public static Function<Double, ImageWrapper> zeroImages() {
        var map = new HashMap<Double, ImageWrapper>();
        return (Double idx) -> map.computeIfAbsent(idx, unused -> ImageWrapper32.createEmpty());
    }

    public ShiftCollectingImageExpressionEvaluator(Broadcaster broadcaster) {
        this(broadcaster, zeroImages());
    }

    public ShiftCollectingImageExpressionEvaluator(Broadcaster broadcaster, Function<Double, ImageWrapper> imageFactory) {
        super(broadcaster, imageFactory);
    }

    @Override
    protected double computePixelShift(ProcessParams params, double targetWaveLength) {
        autoWavelenghts.add(targetWaveLength);
        return super.computePixelShift(params, targetWaveLength);
    }

    @Override
    public Object functionCall(BuiltinFunction function, List<Object> arguments) {
        if (function == BuiltinFunction.CONTINUUM) {
            autoContinuum = true;
        }
        return super.functionCall(function, arguments);
    }

    protected ImageWrapper findImage(double shift) {
        shifts.add(shift);
        return cache.computeIfAbsent(shift, s -> FileBackedImage.wrap(super.findImage(s)));
    }

    public Set<Double> getShifts() {
        return Collections.unmodifiableSet(shifts);
    }

    public Set<Double> getAutoWavelenghts() {
        return Collections.unmodifiableSet(autoWavelenghts);
    }

    public boolean usesAutoContinuum() {
        return autoContinuum;
    }

    public void clearShifts() {
        shifts.clear();
        autoWavelenghts.clear();
        autoContinuum = false;
    }

    public void clearCache() {
        cache.clear();
    }
}
