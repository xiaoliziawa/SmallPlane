package com.lirxowo.smallplane.earlydisplay;

import net.neoforged.fml.earlydisplay.render.SimpleBufferBuilder;

final class QuadBatch {
    private final SimpleBufferBuilder buffer;

    QuadBatch(SimpleBufferBuilder buffer) {
        this.buffer = buffer;
    }

    void begin() {
        buffer.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
    }

    void draw() {
        buffer.draw();
    }

    void colored(float x0, float y0, float x1, float y1, int argb) {
        textured(x0, y0, x1, y1, 0f, 0f, 0f, 0f, argb);
    }

    void textured(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int argb) {
        buffer.pos(x0, y0).tex(u0, v0).colour(argb).endVertex();
        buffer.pos(x1, y0).tex(u1, v0).colour(argb).endVertex();
        buffer.pos(x0, y1).tex(u0, v1).colour(argb).endVertex();
        buffer.pos(x1, y1).tex(u1, v1).colour(argb).endVertex();
    }
}
