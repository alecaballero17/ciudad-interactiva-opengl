package com.graphics;

/** Estado y transformaciones de conducción del automóvil del jugador. */
public final class Vehiculo {
    private final float inicioX;
    private final float inicioZ;
    private float x;
    private float z;
    private float angulo;

    public Vehiculo(float inicioX, float inicioZ) {
        this.inicioX = inicioX;
        this.inicioZ = inicioZ;
        reiniciar();
    }

    public float getX() { return x; }
    public float getZ() { return z; }
    public float getAngulo() { return angulo; }

    public void girar(float grados) { angulo += grados; }

    public void moverA(float nuevoX, float nuevoZ) {
        x = nuevoX;
        z = nuevoZ;
    }

    public void reiniciar() {
        x = inicioX;
        z = inicioZ;
        angulo = 0.0f;
    }
}
