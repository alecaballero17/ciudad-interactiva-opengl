package com.graphics;

/** Estado y transformaciones de conducción del automóvil del jugador. */
public final class Vehiculo {
    private final float inicioX;
    private final float inicioZ;
    private float x;
    private float z;
    private float angulo;
    private float velocidad;

    public Vehiculo(float inicioX, float inicioZ) {
        this.inicioX = inicioX;
        this.inicioZ = inicioZ;
        reiniciar();
    }

    public float getX() { return x; }
    public float getZ() { return z; }
    public float getAngulo() { return angulo; }
    public float getVelocidad() { return velocidad; }

    public void girar(float grados) { angulo += grados; }

    public void moverA(float nuevoX, float nuevoZ) {
        x = nuevoX;
        z = nuevoZ;
    }

    public void acelerar(float incremento, float maxima) {
        velocidad = Math.min(maxima, velocidad + incremento);
    }

    public void retroceder(float incremento, float minima) {
        velocidad = Math.max(minima, velocidad - incremento);
    }

    /** Lleva la velocidad a cero desde cualquier dirección de marcha. */
    public void reducirHaciaCero(float reduccion) {
        if (velocidad > 0.0f) velocidad = Math.max(0.0f, velocidad - reduccion);
        else if (velocidad < 0.0f) velocidad = Math.min(0.0f, velocidad + reduccion);
    }

    public void detener() { velocidad = 0.0f; }

    public void reiniciar() {
        x = inicioX;
        z = inicioZ;
        angulo = 0.0f;
        velocidad = 0.0f;
    }
}
