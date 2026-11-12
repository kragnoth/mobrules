package com.kragnoth.mobrules.rule;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

/** A weighted random pick. */
public final class Weighted<T> {
    private final List<T> items = new ArrayList<>();
    private final List<Double> weights = new ArrayList<>();
    private double total;

    public void add(T item, double weight) {
        if (weight <= 0) return;
        items.add(item);
        weights.add(weight);
        total += weight;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @Nullable
    public T pick(RandomSource r) {
        if (items.isEmpty()) return null;
        double x = r.nextDouble() * total;
        for (int i = 0; i < items.size(); i++) {
            x -= weights.get(i);
            if (x < 0) return items.get(i);
        }
        return items.get(items.size() - 1);
    }
}
