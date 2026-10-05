package com.graphics;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Ciudad interactiva: W/S avanzar-retroceder, A/D girar, R reiniciar,
 * C cambiar cámara, N día/noche, F focos, M minimapa y ESC salir. */
public final class AppCiudad {
    private static final int ANCHO = 1280, ALTO = 720;
    private static final int TAMANO_CIUDAD = 11;
    private static final float CELDA = 10.0f, LIMITE = 55.0f;
    private static final float AUTO_ANCHO = 1.20f, AUTO_LARGO = 2.20f;
    private static final float VELOCIDAD = 15.0f, GIRO = 115.0f;
    private static final float RADIO_ENTREGA = 3.5f;
    private static final float[][] INTERSECCIONES = {
        {-20, -20}, {0, -20}, {40, -20}, {-20, 40}, {0, 40}, {40, 40}
    };

    /* 0 = calle, 1 = manzana/edificio, 2 = parque. */
    private static final int[][] CIUDAD = {
        {1,1,1,0,0,0,1,1,1,0,0},
        {1,1,1,0,2,0,1,1,1,0,2},
        {1,1,1,0,0,0,1,1,1,0,0},
        {0,0,0,0,0,0,0,0,0,0,0},
        {2,0,1,1,1,0,2,0,1,1,1},
        {0,0,1,1,1,0,0,0,1,1,1},
        {1,1,1,0,0,0,1,1,1,0,0},
        {1,1,1,0,2,0,1,1,1,0,2},
        {1,1,1,0,0,0,1,1,1,0,0},
        {0,0,0,0,0,0,0,0,0,0,0},
        {1,1,1,0,2,0,1,1,1,0,0}
    };

    private long window;
    private int programa, programaMapa, vaoCubo, vboCubo, vaoMapa, vboMapa;
    private int uModelo, uVista, uProyeccion, uColor, uEmision, uCamara, uSol, uNoche, uModoMapa;
    private int uLamparas, uFocosPos, uFocosDir, uFocosActivos;
    private final List<Caja> edificios = new ArrayList<>();
    private final float[] vista = identidad(), proyeccion = identidad();
    private final float[] vistaMinimapa = identidad(), proyeccionMinimapa = identidad();
    private final float[] posicionLamparas = new float[9 * 3];
    private final float[] posicionFocos = new float[2 * 3], direccionFocos = new float[2 * 3];
    private final List<float[]> calles = new ArrayList<>();
    private final Random aleatorio = new Random();
    private final Vehiculo vehiculo = new Vehiculo(0.0f, -48.0f);
    private float destinoX, destinoZ;
    private int entregasCompletadas;
    private boolean noche, focos = true, camaraOrbital, minimapaVisible = true;

    public static void main(String[] args) { new AppCiudad().run(); }

    private void run() {
        inicializar();
        bucle();
        liberar();
    }

    private void inicializar() {
        if (!GLFW.glfwInit()) throw new IllegalStateException("No se pudo inicializar GLFW");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "Ciudad interactiva - Fases 1 a 4", 0, 0);
        if (window == 0) throw new IllegalStateException("No se pudo crear la ventana GLFW");
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, accion, mods) -> {
            if (accion != GLFW.GLFW_PRESS) return;
            if (key == GLFW.GLFW_KEY_ESCAPE) GLFW.glfwSetWindowShouldClose(w, true);
            if (key == GLFW.GLFW_KEY_N) noche = !noche;
            if (key == GLFW.GLFW_KEY_F) focos = !focos;
            if (key == GLFW.GLFW_KEY_C) camaraOrbital = !camaraOrbital;
            if (key == GLFW.GLFW_KEY_M) minimapaVisible = !minimapaVisible;
            if (key == GLFW.GLFW_KEY_R) reiniciarAuto();
        });
        GLFW.glfwSetFramebufferSizeCallback(window, (w, ancho, alto) -> GL11.glViewport(0, 0, ancho, alto));
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // La ciudad se observa desde muy cerca y también desde arriba en el minimapa.
        // Dibujamos ambas caras para que calles y techos nunca desaparezcan por culling.
        GL11.glDisable(GL11.GL_CULL_FACE);
        crearShaders();
        crearCuboCompleto();
        crearMapaUI();
        crearCiudad();
        crearLamparas();
        generarNuevoDestino();
        actualizarTitulo();
        GLFW.glfwShowWindow(window);
    }

    private void crearShaders() {
        String vertice = """
            #version 330 core
            layout(location=0) in vec3 aPos;
            layout(location=1) in vec3 aNormal;
            uniform mat4 uModelo, uVista, uProyeccion;
            out vec3 posMundo; out vec3 normalMundo;
            void main() {
                vec4 mundo = uModelo * vec4(aPos, 1.0);
                posMundo = mundo.xyz;
                normalMundo = mat3(transpose(inverse(uModelo))) * aNormal;
                gl_Position = uProyeccion * uVista * mundo;
            }
            """;
        String fragmento = """
            #version 330 core
            in vec3 posMundo; in vec3 normalMundo;
            out vec4 fragColor;
            uniform vec3 uColor, uEmision, uCamara, uSol;
            uniform bool uNoche, uFocosActivos, uModoMapa;
            uniform vec3 uLamparas[9], uFocosPos[2], uFocosDir[2];
            void main() {
                if (uModoMapa) {
                    fragColor = vec4(uColor + uEmision, 1.0);
                    return;
                }
                vec3 n = normalize(normalMundo);
                vec3 luz = (uNoche ? vec3(0.10, 0.12, 0.20) : vec3(0.48, 0.52, 0.58));
                float difSol = max(dot(n, normalize(-uSol)), 0.0);
                luz += (uNoche ? vec3(0.10, 0.13, 0.24) : vec3(0.72, 0.68, 0.58)) * difSol;
                for (int i = 0; i < 9; ++i) {
                    vec3 v = uLamparas[i] - posMundo; float d = length(v);
                    float dif = max(dot(n, normalize(v)), 0.0);
                    luz += vec3(1.0, 0.62, 0.22) * dif / (1.0 + 0.045*d + 0.018*d*d);
                }
                if (uFocosActivos) for (int i = 0; i < 2; ++i) {
                    vec3 v = uFocosPos[i] - posMundo; float d = length(v);
                    vec3 haciaFragmento = normalize(posMundo - uFocosPos[i]);
                    float cono = smoothstep(0.72, 0.91, dot(normalize(uFocosDir[i]), haciaFragmento));
                    float dif = max(dot(n, normalize(v)), 0.0);
                    luz += vec3(0.92, 0.95, 1.0) * dif * cono / (1.0 + 0.09*d + 0.025*d*d);
                }
                fragColor = vec4(uColor * luz + uEmision, 1.0);
            }
            """;
        programa = enlazar(vertice, fragmento);
        uModelo = GL20.glGetUniformLocation(programa, "uModelo");
        uVista = GL20.glGetUniformLocation(programa, "uVista");
        uProyeccion = GL20.glGetUniformLocation(programa, "uProyeccion");
        uColor = GL20.glGetUniformLocation(programa, "uColor");
        uEmision = GL20.glGetUniformLocation(programa, "uEmision");
        uCamara = GL20.glGetUniformLocation(programa, "uCamara");
        uSol = GL20.glGetUniformLocation(programa, "uSol");
        uNoche = GL20.glGetUniformLocation(programa, "uNoche");
        uModoMapa = GL20.glGetUniformLocation(programa, "uModoMapa");
        uLamparas = GL20.glGetUniformLocation(programa, "uLamparas");
        uFocosPos = GL20.glGetUniformLocation(programa, "uFocosPos");
        uFocosDir = GL20.glGetUniformLocation(programa, "uFocosDir");
        uFocosActivos = GL20.glGetUniformLocation(programa, "uFocosActivos");
    }

    private int enlazar(String fuenteVertice, String fuenteFragmento) {
        int vs = compilar(GL20.GL_VERTEX_SHADER, fuenteVertice);
        int fs = compilar(GL20.GL_FRAGMENT_SHADER, fuenteFragmento);
        int p = GL20.glCreateProgram(); GL20.glAttachShader(p, vs); GL20.glAttachShader(p, fs); GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
        GL20.glDeleteShader(vs); GL20.glDeleteShader(fs); return p;
    }

    private int compilar(int tipo, String fuente) {
        int shader = GL20.glCreateShader(tipo); GL20.glShaderSource(shader, fuente); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    /** Shader sin iluminación para el minimapa: funciona como una interfaz 2D nítida. */
    private void crearMapaUI() {
        String vertice = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec3 aColor;
            out vec3 vColor;
            void main() { vColor = aColor; gl_Position = vec4(aPos, 0.0, 1.0); }
            """;
        String fragmento = """
            #version 330 core
            in vec3 vColor;
            out vec4 fragColor;
            void main() { fragColor = vec4(vColor, 1.0); }
            """;
        programaMapa = enlazar(vertice, fragmento);
        vaoMapa = GL30.glGenVertexArrays();
        vboMapa = GL15.glGenBuffers();
        GL30.glBindVertexArray(vaoMapa);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboMapa);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 4096L * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 5 * Float.BYTES, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 5 * Float.BYTES, 2L * Float.BYTES);
        GL20.glEnableVertexAttribArray(0);
        GL20.glEnableVertexAttribArray(1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    private void crearCuboCompleto() {
        float[] v = {
            -1,-1, 1,0,0,1, 1,-1, 1,0,0,1, 1, 1, 1,0,0,1,  -1,-1, 1,0,0,1, 1, 1, 1,0,0,1, -1, 1, 1,0,0,1,
             1,-1,-1,0,0,-1,-1,-1,-1,0,0,-1,-1, 1,-1,0,0,-1, 1,-1,-1,0,0,-1,-1, 1,-1,0,0,-1, 1, 1,-1,0,0,-1,
            -1,-1,-1,-1,0,0,-1,-1, 1,-1,0,0,-1, 1, 1,-1,0,0, -1,-1,-1,-1,0,0,-1, 1, 1,-1,0,0,-1, 1,-1,-1,-1,0,0,
             1,-1, 1,1,0,0, 1,-1,-1,1,0,0, 1, 1,-1,1,0,0,  1,-1, 1,1,0,0, 1, 1,-1,1,0,0, 1, 1, 1,1,0,0,
            -1, 1, 1,0,1,0, 1, 1, 1,0,1,0, 1, 1,-1,0,1,0, -1, 1, 1,0,1,0, 1, 1,-1,0,1,0,-1, 1,-1,0,1,0,
            -1,-1,-1,0,-1,0, 1,-1,-1,0,-1,0, 1,-1, 1,0,-1,0,-1,-1,-1,0,-1,0, 1,-1, 1,0,-1,0,-1,-1, 1,0,-1,0
        };
        vaoCubo = GL30.glGenVertexArrays(); vboCubo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vaoCubo); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboCubo);
        FloatBuffer datos = BufferUtils.createFloatBuffer(v.length); datos.put(v).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, datos, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(0); GL20.glEnableVertexAttribArray(1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL30.glBindVertexArray(0);
    }

    private void crearCiudad() {
        for (int fila = 0; fila < TAMANO_CIUDAD; fila++) for (int columna = 0; columna < TAMANO_CIUDAD; columna++) {
            float x = (columna - 5) * CELDA, z = (fila - 5) * CELDA;
            if (CIUDAD[fila][columna] == 1) edificios.add(new Caja(x, z, 4.25f, 4.25f));
            if (CIUDAD[fila][columna] == 0) calles.add(new float[]{x, z});
        }
    }

    private void crearLamparas() {
        int k = 0;
        for (float z : new float[]{-50, 0, 50}) for (float x : new float[]{-50, 0, 50}) {
            posicionLamparas[k++] = x; posicionLamparas[k++] = 7.0f; posicionLamparas[k++] = z;
        }
    }

    private void bucle() {
        double anterior = GLFW.glfwGetTime();
        while (!GLFW.glfwWindowShouldClose(window)) {
            double ahora = GLFW.glfwGetTime(); float dt = Math.min((float) (ahora - anterior), 0.05f); anterior = ahora;
            procesarMovimiento(dt); actualizarMision(); renderizar((float) ahora);
            GLFW.glfwSwapBuffers(window); GLFW.glfwPollEvents();
        }
    }

    private void procesarMovimiento(float dt) {
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) vehiculo.girar(GIRO * dt);
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) vehiculo.girar(-GIRO * dt);
        float movimiento = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) movimiento += VELOCIDAD * dt;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) movimiento -= VELOCIDAD * dt;
        if (movimiento != 0) {
            float rad = (float) Math.toRadians(vehiculo.getAngulo());
            float candidatoX = vehiculo.getX() + (float) Math.sin(rad) * movimiento;
            float candidatoZ = vehiculo.getZ() + (float) Math.cos(rad) * movimiento;
            if (!colisiona(candidatoX, candidatoZ)) vehiculo.moverA(candidatoX, candidatoZ);
        }
    }

    private boolean colisiona(float x, float z) {
        if (x - AUTO_ANCHO < -LIMITE || x + AUTO_ANCHO > LIMITE || z - AUTO_LARGO < -LIMITE || z + AUTO_LARGO > LIMITE) return true;
        for (Caja e : edificios) if (Math.abs(x - e.x) < AUTO_ANCHO + e.mitadX && Math.abs(z - e.z) < AUTO_LARGO + e.mitadZ) return true;
        return false;
    }

    private void reiniciarAuto() {
        vehiculo.reiniciar();
        entregasCompletadas = 0;
        generarNuevoDestino();
        actualizarTitulo();
    }

    private void actualizarTitulo() {
        GLFW.glfwSetWindowTitle(window, "Ciudad interactiva - Entregas completadas: " + entregasCompletadas);
    }

    private void actualizarMision() {
        float dx = vehiculo.getX() - destinoX, dz = vehiculo.getZ() - destinoZ;
        if (dx * dx + dz * dz <= RADIO_ENTREGA * RADIO_ENTREGA) {
            entregasCompletadas++;
            generarNuevoDestino();
            actualizarTitulo();
        }
    }

    private void generarNuevoDestino() {
        if (calles.isEmpty()) return;
        for (int intento = 0; intento < 40; intento++) {
            float[] calle = calles.get(aleatorio.nextInt(calles.size()));
            float dx = calle[0] - vehiculo.getX(), dz = calle[1] - vehiculo.getZ();
            if (dx * dx + dz * dz > 20 * 20) { destinoX = calle[0]; destinoZ = calle[1]; return; }
        }
        float[] calle = calles.get(aleatorio.nextInt(calles.size()));
        destinoX = calle[0]; destinoZ = calle[1];
    }

    private void renderizar(float tiempo) {
        GL11.glClearColor(noche ? 0.008f : 0.34f, noche ? 0.015f : 0.55f, noche ? 0.045f : 0.82f, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        int[] ancho = new int[1], alto = new int[1];
        GLFW.glfwGetFramebufferSize(window, ancho, alto);
        GL11.glViewport(0, 0, ancho[0], alto[0]);
        float aspecto = alto[0] == 0 ? 1 : (float) ancho[0] / alto[0];
        perspectiva(proyeccion, 58, aspecto, 0.1f, 250);
        float camX, camY, camZ, objetivoX, objetivoY, objetivoZ;
        if (camaraOrbital) {
            float a = tiempo * 0.18f; camX = (float) Math.sin(a) * 92; camZ = (float) Math.cos(a) * 92; camY = 70;
            objetivoX = 0; objetivoY = 0; objetivoZ = 0;
        } else {
            float r = (float) Math.toRadians(vehiculo.getAngulo()); camX = vehiculo.getX() - (float) Math.sin(r) * 14; camZ = vehiculo.getZ() - (float) Math.cos(r) * 14; camY = 8;
            objetivoX = vehiculo.getX() + (float) Math.sin(r) * 6; objetivoY = 1.1f; objetivoZ = vehiculo.getZ() + (float) Math.cos(r) * 6;
        }
        mirar(vista, camX, camY, camZ, objetivoX, objetivoY, objetivoZ, 0, 1, 0);
        dibujarEscena(tiempo, vista, proyeccion, camX, camY, camZ);

        if (minimapaVisible) {
            int lado = Math.min(310, Math.min(ancho[0], alto[0]) / 3);
            int mapaX = ancho[0] - lado - 18, mapaY = alto[0] - lado - 18;
            // Limpiamos solo el rectángulo del minimapa; la escena principal no se altera.
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(mapaX, mapaY, lado, lado);
            GL11.glClearColor(0.025f, 0.035f, 0.065f, 1);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glViewport(mapaX, mapaY, lado, lado);
            dibujarMapaUI(tiempo);
            GL11.glViewport(0, 0, ancho[0], alto[0]);
        }
    }

    private void dibujarEscena(float tiempo, float[] matrizVista, float[] matrizProyeccion, float camX, float camY, float camZ) {
        GL20.glUseProgram(programa);
        GL20.glUniformMatrix4fv(uVista, false, matrizVista); GL20.glUniformMatrix4fv(uProyeccion, false, matrizProyeccion);
        GL20.glUniform3f(uCamara, camX, camY, camZ); GL20.glUniform3f(uSol, -0.35f, -1.0f, -0.25f);
        GL20.glUniform1i(uNoche, noche ? 1 : 0); GL20.glUniform1i(uModoMapa, 0); GL20.glUniform3fv(uLamparas, posicionLamparas);
        actualizarFocos(); GL20.glUniform3fv(uFocosPos, posicionFocos); GL20.glUniform3fv(uFocosDir, direccionFocos); GL20.glUniform1i(uFocosActivos, focos ? 1 : 0);
        GL30.glBindVertexArray(vaoCubo);
        dibujarCubo(0, -0.35f, 0, 110, 0.5f, 110, 0, 0.14f, 0.15f, 0.17f, 0, 0, 0);
        dibujarCiudad(); dibujarPasosPeatonales(); dibujarLamparas(); dibujarSemaforos(tiempo); dibujarDestino(tiempo); dibujarAuto();
        GL30.glBindVertexArray(0); GL20.glUseProgram(0);
    }

    /** Construye un minimapa 2D contrastado: la ciudad se lee de un vistazo. */
    private void dibujarMapaUI(float tiempo) {
        float[] vertices = new float[4096];
        int indice = 0;
        // Panel interior y cuadrícula urbana. El eje Z negativo queda arriba (norte).
        indice = agregarRect(vertices, indice, -.94f, -.94f, .94f, .94f, .025f, .045f, .085f);
        for (int fila = 0; fila < TAMANO_CIUDAD; fila++) for (int columna = 0; columna < TAMANO_CIUDAD; columna++) {
            float x = (columna - 5) * CELDA / 62.0f;
            float y = -((fila - 5) * CELDA) / 62.0f;
            float mitad = 4.15f / 62.0f;
            int tipo = CIUDAD[fila][columna];
            if (tipo == 0) indice = agregarRect(vertices, indice, x - mitad, y - mitad, x + mitad, y + mitad, .17f, .23f, .33f);
            if (tipo == 1) indice = agregarRect(vertices, indice, x - mitad, y - mitad, x + mitad, y + mitad, .08f, .42f, .72f);
            if (tipo == 2) indice = agregarRect(vertices, indice, x - mitad, y - mitad, x + mitad, y + mitad, .08f, .56f, .24f);
        }

        float escala = 1.0f / 62.0f;
        float xAuto = vehiculo.getX() * escala, yAuto = -vehiculo.getZ() * escala;
        float r = (float) Math.toRadians(vehiculo.getAngulo());
        float frenteX = (float) Math.sin(r), frenteY = -(float) Math.cos(r);
        float ladoX = (float) Math.cos(r), ladoY = (float) Math.sin(r);
        // Flecha turquesa del jugador: punta, esquina trasera izquierda y derecha.
        indice = agregarTriangulo(vertices, indice,
                xAuto + frenteX * .055f, yAuto + frenteY * .055f,
                xAuto - frenteX * .038f + ladoX * .035f, yAuto - frenteY * .038f + ladoY * .035f,
                xAuto - frenteX * .038f - ladoX * .035f, yAuto - frenteY * .038f - ladoY * .035f,
                .10f, .98f, .92f);

        float pulso = .042f + .008f * (float) Math.sin(tiempo * 4.0f);
        float xDestino = destinoX * escala, yDestino = -destinoZ * escala;
        indice = agregarTriangulo(vertices, indice, xDestino, yDestino + pulso, xDestino + pulso, yDestino, xDestino, yDestino - pulso, .96f, .18f, .98f);
        indice = agregarTriangulo(vertices, indice, xDestino, yDestino + pulso, xDestino - pulso, yDestino, xDestino, yDestino - pulso, .96f, .18f, .98f);

        FloatBuffer datos = BufferUtils.createFloatBuffer(indice);
        datos.put(vertices, 0, indice).flip();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL20.glUseProgram(programaMapa);
        GL30.glBindVertexArray(vaoMapa);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboMapa);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, datos);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, indice / 5);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private int agregarRect(float[] datos, int i, float izquierda, float abajo, float derecha, float arriba, float r, float g, float b) {
        i = agregarVertice(datos, i, izquierda, abajo, r, g, b);
        i = agregarVertice(datos, i, derecha, abajo, r, g, b);
        i = agregarVertice(datos, i, derecha, arriba, r, g, b);
        i = agregarVertice(datos, i, izquierda, abajo, r, g, b);
        i = agregarVertice(datos, i, derecha, arriba, r, g, b);
        return agregarVertice(datos, i, izquierda, arriba, r, g, b);
    }

    private int agregarTriangulo(float[] datos, int i, float x1, float y1, float x2, float y2, float x3, float y3, float r, float g, float b) {
        i = agregarVertice(datos, i, x1, y1, r, g, b);
        i = agregarVertice(datos, i, x2, y2, r, g, b);
        return agregarVertice(datos, i, x3, y3, r, g, b);
    }

    private int agregarVertice(float[] datos, int i, float x, float y, float r, float g, float b) {
        datos[i++] = x; datos[i++] = y; datos[i++] = r; datos[i++] = g; datos[i++] = b;
        return i;
    }

    /** Vista cenital esquemática: prioriza información legible sobre detalle 3D. */
    private void dibujarMinimapa(float tiempo) {
        GL20.glUseProgram(programa);
        GL20.glUniformMatrix4fv(uVista, false, vistaMinimapa);
        GL20.glUniformMatrix4fv(uProyeccion, false, proyeccionMinimapa);
        GL20.glUniform3f(uCamara, 0, 122, 0);
        GL20.glUniform3f(uSol, -0.35f, -1.0f, -0.25f);
        GL20.glUniform1i(uNoche, 0);
        GL20.glUniform1i(uModoMapa, 1);
        GL20.glUniform3fv(uLamparas, posicionLamparas);
        GL20.glUniform1i(uFocosActivos, 0);
        GL30.glBindVertexArray(vaoCubo);

        // Marco interior oscuro y plano de base para separar claramente el panel de la escena.
        dibujarCubo(0, -.50f, 0, 116, .30f, 116, 0, .018f, .030f, .060f, .01f, .02f, .05f);
        for (int fila = 0; fila < TAMANO_CIUDAD; fila++) for (int columna = 0; columna < TAMANO_CIUDAD; columna++) {
            float x = (columna - 5) * CELDA, z = (fila - 5) * CELDA;
            switch (CIUDAD[fila][columna]) {
                case 0 -> dibujarCubo(x, -.15f, z, 9.25f, .20f, 9.25f, 0, .13f, .16f, .22f, .035f, .045f, .07f);
                case 1 -> dibujarCubo(x, .22f, z, 8.25f, .72f, 8.25f, 0, .10f, .36f, .58f, .03f, .13f, .22f);
                case 2 -> dibujarCubo(x, .18f, z, 8.25f, .60f, 8.25f, 0, .06f, .38f, .15f, .02f, .15f, .05f);
                default -> throw new IllegalStateException("Tipo de celda desconocido");
            }
        }

        float r = (float) Math.toRadians(vehiculo.getAngulo());
        float adelanteX = (float) Math.sin(r), adelanteZ = (float) Math.cos(r);
        // Marcador del auto: franja turquesa más una punta clara que indica dirección.
        dibujarCubo(vehiculo.getX(), .72f, vehiculo.getZ(), 2.25f, .30f, 4.30f, vehiculo.getAngulo(), .03f, .88f, .86f, .02f, .50f, .46f);
        dibujarCubo(vehiculo.getX() + adelanteX * 1.55f, .78f, vehiculo.getZ() + adelanteZ * 1.55f, 1.55f, .32f, 1.15f, vehiculo.getAngulo(), .92f, .98f, 1.0f, .45f, .50f, .52f);
        float pulso = .52f + .12f * (float) Math.sin(tiempo * 4.0f);
        dibujarCubo(destinoX, .72f, destinoZ, 3.0f * pulso, .34f, 3.0f * pulso, tiempo * 50, .82f, .12f, .96f, .58f, .04f, .72f);

        GL30.glBindVertexArray(0);
        GL20.glUseProgram(0);
    }

    private void dibujarCiudad() {
        for (int fila = 0; fila < TAMANO_CIUDAD; fila++) for (int columna = 0; columna < TAMANO_CIUDAD; columna++) {
            float x = (columna - 5) * CELDA, z = (fila - 5) * CELDA;
            int tipo = CIUDAD[fila][columna];
            if (tipo == 0) {
                dibujarCubo(x, -0.05f, z, 9.8f, 0.12f, 9.8f, 0, 0.20f, 0.23f, 0.28f, 0, 0, 0);
                dibujarMarcasViales(x, z, fila, columna);
            } else if (tipo == 1) {
                float alto = 9 + ((fila * 7 + columna * 5) % 4) * 4;
                float r = 0.22f + (columna % 3) * .06f, g = .25f + (fila % 3) * .05f;
                dibujarCubo(x, alto / 2, z, 8.5f, alto, 8.5f, 0, r, g, 0.34f, 0, 0, 0);
                dibujarCubo(x, alto + .25f, z, 8.7f, .5f, 8.7f, 0, .16f, .18f, .22f, 0, 0, 0);
                dibujarVentanasEdificio(x, z, alto);
            } else {
                dibujarCubo(x, -0.02f, z, 9.4f, .18f, 9.4f, 0, .08f, .28f, .10f, 0, 0, 0);
                dibujarArbol(x - 2.5f, z - 1.6f, 1.0f);
                dibujarArbol(x + 2.3f, z + 1.7f, .8f);
                dibujarBanco(x - 1.5f, z + 3.2f, 0);
                dibujarBanco(x + 3.1f, z - 2.8f, 90);
            }
        }
    }

    private void dibujarMarcasViales(float x, float z, int fila, int columna) {
        // Carriles discontinuos: verticales en los corredores norte-sur y horizontales en los demás.
        boolean ejeVertical = columna == 3 || columna == 5 || columna == 9;
        for (float tramo = -3.4f; tramo <= 3.4f; tramo += 3.4f) {
            if (ejeVertical) dibujarCubo(x, .025f, z + tramo, .16f, .035f, 1.05f, 0, .88f, .78f, .26f, 0, 0, 0);
            else dibujarCubo(x + tramo, .025f, z, 1.05f, .035f, .16f, 0, .88f, .78f, .26f, 0, 0, 0);
        }
    }

    private void dibujarVentanasEdificio(float x, float z, float alto) {
        float brillo = noche ? .92f : .08f;
        for (float y = 2.1f; y < alto - 1; y += 3.2f) {
            for (float desplazamiento : new float[]{-2.65f, 0, 2.65f}) {
                dibujarCubo(x + desplazamiento, y, z + 4.28f, 1.25f, 1.15f, .08f, 0, .20f, .52f, .72f, brillo * .32f, brillo * .72f, brillo);
                dibujarCubo(x + desplazamiento, y, z - 4.28f, 1.25f, 1.15f, .08f, 0, .20f, .52f, .72f, brillo * .32f, brillo * .72f, brillo);
                dibujarCubo(x + 4.28f, y, z + desplazamiento, .08f, 1.15f, 1.25f, 0, .20f, .52f, .72f, brillo * .32f, brillo * .72f, brillo);
                dibujarCubo(x - 4.28f, y, z + desplazamiento, .08f, 1.15f, 1.25f, 0, .20f, .52f, .72f, brillo * .32f, brillo * .72f, brillo);
            }
        }
    }

    private void dibujarArbol(float x, float z, float escala) {
        dibujarCubo(x, 1.5f * escala, z, .65f * escala, 3.0f * escala, .65f * escala, 0, .30f, .16f, .055f, 0, 0, 0);
        dibujarCubo(x, 3.5f * escala, z, 2.7f * escala, 2.0f * escala, 2.7f * escala, 0, .035f, .32f, .07f, 0, 0, 0);
        dibujarCubo(x + .7f * escala, 4.2f * escala, z, 1.9f * escala, 1.5f * escala, 1.9f * escala, 0, .04f, .42f, .09f, 0, 0, 0);
        dibujarCubo(x - .6f * escala, 4.0f * escala, z + .5f * escala, 1.8f * escala, 1.4f * escala, 1.8f * escala, 0, .03f, .37f, .08f, 0, 0, 0);
    }

    private void dibujarBanco(float x, float z, float angulo) {
        dibujarCubo(x, .85f, z, 3.2f, .22f, .62f, angulo, .42f, .20f, .07f, 0, 0, 0);
        dibujarCubo(x, 1.45f, z + (angulo == 0 ? -.23f : 0), 3.2f, .65f, .18f, angulo, .42f, .20f, .07f, 0, 0, 0);
        dibujarCubo(x - 1.18f, .42f, z, .18f, .85f, .50f, angulo, .12f, .12f, .13f, 0, 0, 0);
        dibujarCubo(x + 1.18f, .42f, z, .18f, .85f, .50f, angulo, .12f, .12f, .13f, 0, 0, 0);
    }

    private void dibujarLamparas() {
        for (int i = 0; i < 9; i++) {
            float x = posicionLamparas[i * 3], z = posicionLamparas[i * 3 + 2];
            dibujarCubo(x, 3.3f, z, .18f, 6.6f, .18f, 0, .12f, .12f, .13f, 0, 0, 0);
            dibujarCubo(x, 6.8f, z, .8f, .35f, .8f, 0, .36f, .28f, .12f, 1.0f, .48f, .08f);
        }
    }

    /** Franjas blancas junto a cada cruce vial; también son visibles en el minimapa. */
    private void dibujarPasosPeatonales() {
        for (float[] cruce : INTERSECCIONES) {
            for (int i = -3; i <= 3; i++) {
                float desplazamiento = i * .72f;
                dibujarCubo(cruce[0] + desplazamiento, .025f, cruce[1] - 3.8f, .34f, .04f, 1.25f, 0, .78f, .78f, .72f, 0, 0, 0);
                dibujarCubo(cruce[0] - 3.8f, .025f, cruce[1] + desplazamiento, 1.25f, .04f, .34f, 0, .78f, .78f, .72f, 0, 0, 0);
            }
        }
    }

    private void dibujarSemaforos(float tiempo) {
        float fase = tiempo % 9.0f;
        int luzActiva = fase < 4.0f ? 0 : (fase < 7.0f ? 1 : 2); // rojo, verde, amarillo
        for (float[] esquina : INTERSECCIONES) {
            float x = esquina[0] + 2.7f, z = esquina[1] + 2.7f;
            dibujarCubo(x, 2.2f, z, .18f, 4.4f, .18f, 0, .09f, .09f, .10f, 0, 0, 0);
            dibujarCubo(x, 4.0f, z, .72f, 1.95f, .52f, 0, .06f, .065f, .07f, 0, 0, 0);
            dibujarLuzSemaforo(x, 4.6f, z + .29f, .92f, .04f, .03f, luzActiva == 0);
            dibujarLuzSemaforo(x, 4.0f, z + .29f, .04f, .88f, .12f, luzActiva == 1);
            dibujarLuzSemaforo(x, 3.4f, z + .29f, .95f, .63f, .03f, luzActiva == 2);
        }
    }

    private void dibujarLuzSemaforo(float x, float y, float z, float r, float g, float b, boolean encendida) {
        float intensidad = encendida ? 1.0f : .025f;
        dibujarCubo(x, y, z, .36f, .30f, .08f, 0, r, g, b, r * intensidad, g * intensidad, b * intensidad);
    }

    private void dibujarDestino(float tiempo) {
        float flotacion = 3.4f + (float) Math.sin(tiempo * 3.2f) * .55f;
        float pulso = .75f + .25f * (float) Math.sin(tiempo * 4.0f);
        dibujarCubo(destinoX, 1.1f, destinoZ, .18f, 2.2f, .18f, 0, .22f, .22f, .25f, .2f, .05f, .7f);
        dibujarCubo(destinoX, flotacion, destinoZ, 1.25f * pulso, 1.25f * pulso, 1.25f * pulso, tiempo * 70, .68f, .16f, .95f, .8f, .12f, 1.0f);
        dibujarCubo(destinoX, .24f, destinoZ, 2.3f, .09f, 2.3f, 0, .45f, .08f, .62f, .38f, .03f, .58f);
    }

    private void dibujarAuto() {
        float r = (float) Math.toRadians(vehiculo.getAngulo());
        float adelanteX = (float) Math.sin(r), adelanteZ = (float) Math.cos(r);
        // Carrocería: paragolpes, capó, cabina y baúl para una silueta reconocible.
        dibujarCubo(vehiculo.getX(), .62f, vehiculo.getZ(), 2.45f, .72f, 4.55f, vehiculo.getAngulo(), .83f, .055f, .035f, 0, 0, 0);
        dibujarCubo(vehiculo.getX() + adelanteX * 1.05f, 1.10f, vehiculo.getZ() + adelanteZ * 1.05f, 2.18f, .42f, 1.62f, vehiculo.getAngulo(), .92f, .075f, .04f, 0, 0, 0);
        dibujarCubo(vehiculo.getX() - adelanteX * .98f, 1.00f, vehiculo.getZ() - adelanteZ * .98f, 2.24f, .48f, 1.54f, vehiculo.getAngulo(), .70f, .035f, .025f, 0, 0, 0);
        dibujarCubo(vehiculo.getX() - adelanteX * .08f, 1.45f, vehiculo.getZ() - adelanteZ * .18f, 1.82f, .86f, 2.05f, vehiculo.getAngulo(), .055f, .26f, .39f, .01f, .03f, .05f);
        // Marcador turquesa sobre el capó: orientación inequívoca en el minimapa.
        dibujarCubo(vehiculo.getX() + adelanteX * 1.63f, 1.36f, vehiculo.getZ() + adelanteZ * 1.63f, .52f, .10f, .78f, vehiculo.getAngulo(), .08f, .92f, .88f, .02f, .35f, .30f);
        for (float lateral : new float[]{-1.15f, 1.15f}) for (float frontal : new float[]{-1.45f, 1.45f}) {
            float wx = vehiculo.getX() + (float) Math.cos(r) * lateral + (float) Math.sin(r) * frontal;
            float wz = vehiculo.getZ() - (float) Math.sin(r) * lateral + (float) Math.cos(r) * frontal;
            dibujarCubo(wx, .39f, wz, .48f, .62f, .90f, vehiculo.getAngulo(), .025f, .028f, .035f, 0, 0, 0);
            dibujarCubo(wx, .40f, wz, .18f, .30f, .94f, vehiculo.getAngulo(), .55f, .58f, .61f, 0, 0, 0);
        }
        float fx = vehiculo.getX() + adelanteX * 2.28f, fz = vehiculo.getZ() + adelanteZ * 2.28f;
        dibujarCubo(fx + (float)Math.cos(r) * .65f, .82f, fz - (float)Math.sin(r) * .65f, .28f, .25f, .12f, vehiculo.getAngulo(), .9f,.9f,.72f, focos?1:.05f, focos?1:.05f, focos?0.55f:.02f);
        dibujarCubo(fx - (float)Math.cos(r) * .65f, .82f, fz + (float)Math.sin(r) * .65f, .28f, .25f, .12f, vehiculo.getAngulo(), .9f,.9f,.72f, focos?1:.05f, focos?1:.05f, focos?0.55f:.02f);
    }

    private void actualizarFocos() {
        float r = (float) Math.toRadians(vehiculo.getAngulo()), adelanteX = (float) Math.sin(r), adelanteZ = (float) Math.cos(r), ladoX = (float) Math.cos(r), ladoZ = -(float) Math.sin(r);
        for (int i = 0; i < 2; i++) {
            float lado = i == 0 ? -.68f : .68f;
            posicionFocos[i * 3] = vehiculo.getX() + adelanteX * 2.2f + ladoX * lado;
            posicionFocos[i * 3 + 1] = .85f;
            posicionFocos[i * 3 + 2] = vehiculo.getZ() + adelanteZ * 2.2f + ladoZ * lado;
            direccionFocos[i * 3] = adelanteX; direccionFocos[i * 3 + 1] = -.08f; direccionFocos[i * 3 + 2] = adelanteZ;
        }
    }

    private void dibujarCubo(float x, float y, float z, float sx, float sy, float sz, float angulo, float r, float g, float b, float er, float eg, float eb) {
        float[] modelo = modelo(x, y, z, sx / 2, sy / 2, sz / 2, angulo);
        GL20.glUniformMatrix4fv(uModelo, false, modelo); GL20.glUniform3f(uColor, r, g, b); GL20.glUniform3f(uEmision, er, eg, eb);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
    }

    private void liberar() {
        GL20.glDeleteProgram(programa); GL20.glDeleteProgram(programaMapa);
        GL15.glDeleteBuffers(vboCubo); GL15.glDeleteBuffers(vboMapa);
        GL30.glDeleteVertexArrays(vaoCubo); GL30.glDeleteVertexArrays(vaoMapa);
        GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate();
    }

    private record Caja(float x, float z, float mitadX, float mitadZ) { }

    private static float[] identidad() { float[] m = new float[16]; m[0] = m[5] = m[10] = m[15] = 1; return m; }
    private static float[] modelo(float x, float y, float z, float sx, float sy, float sz, float grados) {
        float r = (float)Math.toRadians(grados), c = (float)Math.cos(r), s = (float)Math.sin(r); float[] m = new float[16];
        m[0]=c*sx; m[1]=0; m[2]=-s*sx; m[3]=0; m[4]=0; m[5]=sy; m[6]=0; m[7]=0; m[8]=s*sz; m[9]=0; m[10]=c*sz; m[11]=0; m[12]=x; m[13]=y; m[14]=z; m[15]=1; return m;
    }
    private static void perspectiva(float[] m, float fov, float aspecto, float cerca, float lejos) {
        for(int i=0;i<16;i++)m[i]=0; float f=1f/(float)Math.tan(Math.toRadians(fov)/2); m[0]=f/aspecto; m[5]=f; m[10]=(lejos+cerca)/(cerca-lejos); m[11]=-1; m[14]=(2*lejos*cerca)/(cerca-lejos);
    }
    private static void ortografica(float[] m, float izquierda, float derecha, float abajo, float arriba, float cerca, float lejos) {
        for (int i = 0; i < 16; i++) m[i] = 0;
        m[0] = 2 / (derecha - izquierda); m[5] = 2 / (arriba - abajo); m[10] = -2 / (lejos - cerca);
        m[12] = -(derecha + izquierda) / (derecha - izquierda); m[13] = -(arriba + abajo) / (arriba - abajo);
        m[14] = -(lejos + cerca) / (lejos - cerca); m[15] = 1;
    }
    private static void mirar(float[] m, float ex,float ey,float ez,float cx,float cy,float cz,float ux,float uy,float uz) {
        float zx=ex-cx,zy=ey-cy,zz=ez-cz, zl=(float)Math.sqrt(zx*zx+zy*zy+zz*zz); zx/=zl;zy/=zl;zz/=zl;
        float xx=uy*zz-uz*zy,xy=uz*zx-ux*zz,xz=ux*zy-uy*zx,xl=(float)Math.sqrt(xx*xx+xy*xy+xz*xz);xx/=xl;xy/=xl;xz/=xl;
        float yx=zy*xz-zz*xy, yy=zz*xx-zx*xz, yz=zx*xy-zy*xx;
        m[0]=xx;m[1]=yx;m[2]=zx;m[3]=0; m[4]=xy;m[5]=yy;m[6]=zy;m[7]=0; m[8]=xz;m[9]=yz;m[10]=zz;m[11]=0; m[12]=-(xx*ex+xy*ey+xz*ez);m[13]=-(yx*ex+yy*ey+yz*ez);m[14]=-(zx*ex+zy*ey+zz*ez);m[15]=1;
    }
}
