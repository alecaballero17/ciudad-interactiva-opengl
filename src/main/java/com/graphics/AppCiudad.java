package com.graphics;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
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
    // Huella de conducción: compacta alrededor de los ejes para no cerrar carriles estrechos.
    // La carrocería conserva su escala visual, pero esta AABB evita paredes invisibles al maniobrar.
    private static final float COLISION_MITAD_ANCHO = .42f, COLISION_MITAD_LARGO = .78f;
    private static final float VELOCIDAD = 15.0f, GIRO = 115.0f;
    private static final float RADIO_ENTREGA = 3.5f;
    private static final float[][] INTERSECCIONES = {
        {-30, -30}, {0, -30}, {30, -30}, {-30, 30}, {0, 30}, {30, 30}
    };

    /* 0 = calle, 1 = manzana/edificio, 2 = parque. */
    private static final int[][] CIUDAD = {
        {1,1,0,1,1,0,1,1,0,1,1},
        {1,2,0,1,2,0,1,2,0,1,1},
        {0,0,0,0,0,0,0,0,0,0,0},
        {1,1,0,1,1,0,1,1,0,1,1},
        {2,1,0,1,1,0,1,2,0,1,1},
        {0,0,0,0,0,0,0,0,0,0,0},
        {1,1,0,1,1,0,1,1,0,1,1},
        {1,2,0,1,2,0,1,2,0,1,1},
        {0,0,0,0,0,0,0,0,0,0,0},
        {1,1,0,1,1,0,1,1,0,1,1},
        {1,1,0,1,2,0,1,1,0,1,1}
    };

    private long window;
    private int programa, programaMapa, vaoCubo, vboCubo, vaoPlano, vboPlano, vaoEsfera, vboEsfera, verticesEsfera, vaoMapa, vboMapa;
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
        crearPlanoPavimento();
        crearEsfera();
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
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 32768L * Float.BYTES, GL15.GL_DYNAMIC_DRAW);
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

    /** Plano horizontal independiente para calzadas y marcas: evita artefactos en caras superiores del cubo. */
    private void crearPlanoPavimento() {
        float[] v = {
            -1,0,-1, 0,1,0,   1,0,-1, 0,1,0,   1,0, 1, 0,1,0,
            -1,0,-1, 0,1,0,   1,0, 1, 0,1,0,  -1,0, 1, 0,1,0
        };
        vaoPlano = GL30.glGenVertexArrays(); vboPlano = GL15.glGenBuffers();
        GL30.glBindVertexArray(vaoPlano); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboPlano);
        FloatBuffer datos = BufferUtils.createFloatBuffer(v.length); datos.put(v).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, datos, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(0); GL20.glEnableVertexAttribArray(1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL30.glBindVertexArray(0);
    }

    /** Esfera con normales para el tanque redondeado del carrito de somó. */
    private void crearEsfera() {
        int sectores = 24, anillos = 16;
        float[] datos = new float[sectores * anillos * 6 * 6];
        int i = 0;
        for (int anillo = 0; anillo < anillos; anillo++) {
            float v0 = (float) anillo / anillos, v1 = (float) (anillo + 1) / anillos;
            float phi0 = (float) (Math.PI * (v0 - .5)), phi1 = (float) (Math.PI * (v1 - .5));
            for (int sector = 0; sector < sectores; sector++) {
                float u0 = (float) sector / sectores, u1 = (float) (sector + 1) / sectores;
                i = agregarVerticeEsfera(datos, i, u0, phi0); i = agregarVerticeEsfera(datos, i, u1, phi0); i = agregarVerticeEsfera(datos, i, u1, phi1);
                i = agregarVerticeEsfera(datos, i, u0, phi0); i = agregarVerticeEsfera(datos, i, u1, phi1); i = agregarVerticeEsfera(datos, i, u0, phi1);
            }
        }
        verticesEsfera = i / 6;
        vaoEsfera = GL30.glGenVertexArrays(); vboEsfera = GL15.glGenBuffers();
        GL30.glBindVertexArray(vaoEsfera); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboEsfera);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(i); buffer.put(datos, 0, i).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(0); GL20.glEnableVertexAttribArray(1);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL30.glBindVertexArray(0);
    }

    private int agregarVerticeEsfera(float[] datos, int i, float u, float phi) {
        float theta = (float) (Math.PI * 2 * u);
        float x = (float) (Math.cos(phi) * Math.cos(theta));
        float y = (float) Math.sin(phi);
        float z = (float) (Math.cos(phi) * Math.sin(theta));
        datos[i++] = x; datos[i++] = y; datos[i++] = z;
        datos[i++] = x; datos[i++] = y; datos[i++] = z;
        return i;
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
        for (float z : new float[]{-30, 0, 30}) for (float x : new float[]{-30, 0, 30}) {
            // Se ubican junto a la acera, nunca en el centro del carril.
            posicionLamparas[k++] = x + 4.25f; posicionLamparas[k++] = 7.0f; posicionLamparas[k++] = z + 4.25f;
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
            if (!colisiona(candidatoX, candidatoZ)) {
                vehiculo.moverA(candidatoX, candidatoZ);
            } else if (!colisiona(candidatoX, vehiculo.getZ())) {
                // Deslizamiento: rozar una esquina no bloquea toda la marcha.
                vehiculo.moverA(candidatoX, vehiculo.getZ());
            } else if (!colisiona(vehiculo.getX(), candidatoZ)) {
                vehiculo.moverA(vehiculo.getX(), candidatoZ);
            }
        }
    }

    private boolean colisiona(float x, float z) {
        float rad = (float) Math.toRadians(vehiculo.getAngulo());
        // Proyección de una caja rotada sobre X/Z: elimina los muros invisibles al girar.
        float mitadX = Math.abs((float) Math.cos(rad)) * COLISION_MITAD_ANCHO + Math.abs((float) Math.sin(rad)) * COLISION_MITAD_LARGO;
        float mitadZ = Math.abs((float) Math.sin(rad)) * COLISION_MITAD_ANCHO + Math.abs((float) Math.cos(rad)) * COLISION_MITAD_LARGO;
        if (x - mitadX < -LIMITE || x + mitadX > LIMITE || z - mitadZ < -LIMITE || z + mitadZ > LIMITE) return true;
        for (Caja e : edificios) if (Math.abs(x - e.x) < mitadX + e.mitadX && Math.abs(z - e.z) < mitadZ + e.mitadZ) return true;
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
            float r = (float) Math.toRadians(vehiculo.getAngulo()); camX = vehiculo.getX() - (float) Math.sin(r) * 17; camZ = vehiculo.getZ() - (float) Math.cos(r) * 17; camY = 9;
            objetivoX = vehiculo.getX() + (float) Math.sin(r) * 7; objetivoY = 1.35f; objetivoZ = vehiculo.getZ() + (float) Math.cos(r) * 7;
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
        dibujarCiudad(); dibujarPasosPeatonales(); dibujarLamparas(); dibujarSemaforos(tiempo); dibujarDestino(tiempo); dibujarMinibus();
        GL30.glBindVertexArray(0); GL20.glUseProgram(0);
    }

    /** Construye un minimapa 2D contrastado: la ciudad se lee de un vistazo. */
    private void dibujarMapaUI(float tiempo) {
        float[] vertices = new float[32768];
        int indice = 0;
        // Panel inspirado en la paleta local: verde cruceño, blanco y piedra cálida.
        indice = agregarRect(vertices, indice, -.97f, -.97f, .97f, .97f, .015f, .18f, .075f);
        indice = agregarRect(vertices, indice, -.925f, -.925f, .925f, .925f, .78f, .73f, .62f);
        indice = agregarMarcoMapa(vertices, indice);
        for (int fila = 0; fila < TAMANO_CIUDAD; fila++) for (int columna = 0; columna < TAMANO_CIUDAD; columna++) {
            float x = (columna - 5) * CELDA / 62.0f;
            float y = -((fila - 5) * CELDA) / 62.0f;
            float mitad = 4.15f / 62.0f;
            int tipo = CIUDAD[fila][columna];
            if (tipo == 0) indice = agregarCalleMapa(vertices, indice, x, y, mitad, columna);
            if (tipo == 1) indice = agregarEdificioMapa(vertices, indice, x, y, mitad, fila, columna);
            if (tipo == 2) indice = agregarParqueMapa(vertices, indice, x, y, mitad, fila, columna);
        }

        float escala = 1.0f / 62.0f;
        float xAuto = vehiculo.getX() * escala, yAuto = -vehiculo.getZ() * escala;
        float xDestino = destinoX * escala, yDestino = -destinoZ * escala;
        float r = (float) Math.toRadians(vehiculo.getAngulo());
        float frenteX = (float) Math.sin(r), frenteY = -(float) Math.cos(r);
        float ladoX = (float) Math.cos(r), ladoY = (float) Math.sin(r);
        indice = agregarRutaMapa(vertices, indice, xAuto, yAuto, xDestino, yDestino);
        // Minibús: silueta blanca alargada, franja azul, zócalo rojo y parabrisas oscuro.
        indice = agregarRectOrientado(vertices, indice, xAuto + .008f, yAuto - .008f, frenteX, frenteY, ladoX, ladoY, .076f, .034f, .008f, .012f, .022f);
        indice = agregarRectOrientado(vertices, indice, xAuto, yAuto, frenteX, frenteY, ladoX, ladoY, .071f, .031f, .88f, .89f, .84f);
        indice = agregarRectOrientado(vertices, indice, xAuto, yAuto, frenteX, frenteY, ladoX, ladoY, .071f, .008f, .04f, .34f, .72f);
        indice = agregarRectOrientado(vertices, indice, xAuto - frenteX * .040f, yAuto - frenteY * .040f, frenteX, frenteY, ladoX, ladoY, .025f, .024f, .05f, .18f, .29f);
        indice = agregarRectOrientado(vertices, indice, xAuto + frenteX * .060f, yAuto + frenteY * .060f, frenteX, frenteY, ladoX, ladoY, .007f, .023f, .96f, .94f, .72f);
        // Flecha sutil encima del minibús para que la orientación sea inequívoca.
        indice = agregarTriangulo(vertices, indice,
                xAuto + frenteX * .079f, yAuto + frenteY * .079f,
                xAuto + frenteX * .045f + ladoX * .018f, yAuto + frenteY * .045f + ladoY * .018f,
                xAuto + frenteX * .045f - ladoX * .018f, yAuto + frenteY * .045f - ladoY * .018f,
                .96f, .98f, 1.0f);

        float pulso = .042f + .008f * (float) Math.sin(tiempo * 4.0f);
        indice = agregarTriangulo(vertices, indice, xDestino, yDestino + pulso, xDestino + pulso, yDestino, xDestino, yDestino - pulso, .04f, .18f, .52f);
        indice = agregarTriangulo(vertices, indice, xDestino, yDestino + pulso, xDestino - pulso, yDestino, xDestino, yDestino - pulso, .04f, .18f, .52f);
        float nucleo = pulso * .52f;
        indice = agregarTriangulo(vertices, indice, xDestino, yDestino + nucleo, xDestino + nucleo, yDestino, xDestino, yDestino - nucleo, .98f, .68f, .08f);
        indice = agregarTriangulo(vertices, indice, xDestino, yDestino + nucleo, xDestino - nucleo, yDestino, xDestino, yDestino - nucleo, .98f, .68f, .08f);
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

    private int agregarCalleMapa(float[] datos, int i, float x, float y, float mitad, int columna) {
        i = agregarRect(datos, i, x - mitad, y - mitad, x + mitad, y + mitad, .18f, .16f, .13f);
        float borde = mitad * .82f;
        i = agregarRect(datos, i, x - borde, y - borde, x + borde, y + borde, .38f, .34f, .28f);
        boolean vertical = columna == 2 || columna == 5 || columna == 8;
        for (float d = -mitad * .55f; d <= mitad * .55f; d += mitad * .55f) {
            if (vertical) i = agregarRect(datos, i, x - .006f, y + d - .018f, x + .006f, y + d + .018f, .82f, .80f, .67f);
            else i = agregarRect(datos, i, x + d - .018f, y - .006f, x + d + .018f, y + .006f, .82f, .80f, .67f);
        }
        return i;
    }

    private int agregarEdificioMapa(float[] datos, int i, float x, float y, float mitad, int fila, int columna) {
        float[][] paleta = {{.69f, .36f, .20f}, {.82f, .72f, .54f}, {.73f, .68f, .58f}, {.58f, .33f, .23f}};
        float[] color = paleta[(fila * 3 + columna) % paleta.length];
        float sesgoX = ((fila * 3 + columna) % 2 == 0) ? -.009f : .009f;
        float sesgoY = ((fila + columna * 2) % 2 == 0) ? .007f : -.007f;
        i = agregarRect(datos, i, x - mitad + .010f, y - mitad - .010f, x + mitad + .010f, y + mitad - .010f, .025f, .024f, .028f);
        i = agregarRect(datos, i, x - mitad * .83f + sesgoX, y - mitad * .83f + sesgoY, x + mitad * .83f + sesgoX, y + mitad * .83f + sesgoY, color[0] * .74f, color[1] * .74f, color[2] * .74f);
        i = agregarRect(datos, i, x - mitad * .58f + sesgoX, y - mitad * .58f + sesgoY, x + mitad * .58f + sesgoX, y + mitad * .58f + sesgoY, color[0], color[1], color[2]);
        // Azotea y detalles de fachada: generan volumen sin saturar el panel.
        i = agregarRect(datos, i, x - mitad * .64f + sesgoX, y + mitad * .38f + sesgoY, x + mitad * .64f + sesgoX, y + mitad * .53f + sesgoY, .74f, .74f, .68f);
        return agregarRect(datos, i, x - mitad * .14f + sesgoX, y - mitad * .42f + sesgoY, x + mitad * .14f + sesgoX, y + mitad * .28f + sesgoY, .48f, .50f, .50f);
    }

    private int agregarParqueMapa(float[] datos, int i, float x, float y, float mitad, int fila, int columna) {
        i = agregarRect(datos, i, x - mitad, y - mitad, x + mitad, y + mitad, .018f, .11f, .042f);
        i = agregarRect(datos, i, x - mitad * .82f, y - mitad * .82f, x + mitad * .82f, y + mitad * .82f, .06f, .35f, .13f);
        float variacion = ((fila + columna) % 2 == 0) ? .018f : -.018f;
        i = agregarRect(datos, i, x - .042f + variacion, y + .005f, x - .006f + variacion, y + .041f, .28f, .74f, .16f);
        i = agregarRect(datos, i, x + .014f - variacion, y - .043f, x + .050f - variacion, y - .007f, .28f, .74f, .16f);
        return agregarRect(datos, i, x - .018f, y - .013f, x + .018f, y + .013f, .70f, .62f, .34f);
    }

    private int agregarRutaMapa(float[] datos, int i, float x1, float y1, float x2, float y2) {
        int inicio = calleMasCercana(vehiculo.getX(), vehiculo.getZ());
        int destino = calleMasCercana(destinoX, destinoZ);
        int[] anterior = new int[TAMANO_CIUDAD * TAMANO_CIUDAD];
        Arrays.fill(anterior, -2);
        int[] cola = new int[anterior.length];
        int primero = 0, ultimo = 0;
        cola[ultimo++] = inicio; anterior[inicio] = -1;
        while (primero < ultimo && anterior[destino] == -2) {
            int actual = cola[primero++], fila = actual / TAMANO_CIUDAD, columna = actual % TAMANO_CIUDAD;
            for (int[] paso : new int[][]{{-1,0}, {1,0}, {0,-1}, {0,1}}) {
                int nuevaFila = fila + paso[0], nuevaColumna = columna + paso[1];
                if (nuevaFila < 0 || nuevaFila >= TAMANO_CIUDAD || nuevaColumna < 0 || nuevaColumna >= TAMANO_CIUDAD) continue;
                int vecino = nuevaFila * TAMANO_CIUDAD + nuevaColumna;
                if (CIUDAD[nuevaFila][nuevaColumna] == 0 && anterior[vecino] == -2) {
                    anterior[vecino] = actual;
                    cola[ultimo++] = vecino;
                }
            }
        }
        if (anterior[destino] == -2) return agregarSegmentoRutaMapa(datos, i, x1, y1, x2, y2);

        List<Integer> camino = new ArrayList<>();
        for (int nodo = destino; nodo != -1; nodo = anterior[nodo]) camino.add(nodo);
        float previoX = x1, previoY = y1;
        for (int p = camino.size() - 1; p >= 0; p--) {
            int nodo = camino.get(p), fila = nodo / TAMANO_CIUDAD, columna = nodo % TAMANO_CIUDAD;
            float puntoX = (columna - 5) * CELDA / 62.0f;
            float puntoY = -((fila - 5) * CELDA) / 62.0f;
            i = agregarSegmentoRutaMapa(datos, i, previoX, previoY, puntoX, puntoY);
            previoX = puntoX; previoY = puntoY;
        }
        i = agregarSegmentoRutaMapa(datos, i, previoX, previoY, x2, y2);
        return i;
    }

    /** Índice de la calle más cercana, usado para anclar la navegación a la red vial. */
    private int calleMasCercana(float x, float z) {
        int mejor = 0; float distanciaMinima = Float.MAX_VALUE;
        for (int fila = 0; fila < TAMANO_CIUDAD; fila++) for (int columna = 0; columna < TAMANO_CIUDAD; columna++) {
            if (CIUDAD[fila][columna] != 0) continue;
            float calleX = (columna - 5) * CELDA, calleZ = (fila - 5) * CELDA;
            float dx = x - calleX, dz = z - calleZ, distancia = dx * dx + dz * dz;
            if (distancia < distanciaMinima) { distanciaMinima = distancia; mejor = fila * TAMANO_CIUDAD + columna; }
        }
        return mejor;
    }

    /** Segmento amarillo con borde oscuro para conservar legibilidad sobre el mapa. */
    private int agregarSegmentoRutaMapa(float[] datos, int i, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1, distancia = (float) Math.sqrt(dx * dx + dy * dy);
        if (distancia < .004f) return i;
        float frenteX = dx / distancia, frenteY = dy / distancia, ladoX = -frenteY, ladoY = frenteX;
        float centroX = (x1 + x2) * .5f, centroY = (y1 + y2) * .5f;
        i = agregarRectOrientado(datos, i, centroX, centroY, frenteX, frenteY, ladoX, ladoY, distancia * .5f, .019f, .13f, .10f, .025f);
        return agregarRectOrientado(datos, i, centroX, centroY, frenteX, frenteY, ladoX, ladoY, distancia * .5f, .012f, .98f, .72f, .08f);
    }

    private int agregarRectOrientado(float[] datos, int i, float x, float y, float frenteX, float frenteY, float ladoX, float ladoY, float medioLargo, float medioAncho, float r, float g, float b) {
        float x1 = x - frenteX * medioLargo - ladoX * medioAncho, y1 = y - frenteY * medioLargo - ladoY * medioAncho;
        float x2 = x + frenteX * medioLargo - ladoX * medioAncho, y2 = y + frenteY * medioLargo - ladoY * medioAncho;
        float x3 = x + frenteX * medioLargo + ladoX * medioAncho, y3 = y + frenteY * medioLargo + ladoY * medioAncho;
        float x4 = x - frenteX * medioLargo + ladoX * medioAncho, y4 = y - frenteY * medioLargo + ladoY * medioAncho;
        i = agregarTriangulo(datos, i, x1, y1, x2, y2, x3, y3, r, g, b);
        return agregarTriangulo(datos, i, x1, y1, x3, y3, x4, y4, r, g, b);
    }

    private int agregarMarcoMapa(float[] datos, int i) {
        float verdeR = .02f, verdeG = .45f, verdeB = .17f;
        i = agregarRect(datos, i, -.965f, .935f, .965f, .965f, verdeR, verdeG, verdeB);
        i = agregarRect(datos, i, -.965f, -.965f, .965f, -.935f, verdeR, verdeG, verdeB);
        i = agregarRect(datos, i, -.965f, -.965f, -.935f, .965f, verdeR, verdeG, verdeB);
        i = agregarRect(datos, i, .935f, -.965f, .965f, .965f, verdeR, verdeG, verdeB);
        // Línea blanca interior: referencia visual directa a la bandera cruceña.
        float blanco = .94f;
        i = agregarRect(datos, i, -.935f, .910f, .935f, .924f, blanco, blanco, blanco);
        i = agregarRect(datos, i, -.935f, -.924f, .935f, -.910f, blanco, blanco, blanco);
        i = agregarRect(datos, i, -.924f, -.910f, -.910f, .910f, blanco, blanco, blanco);
        return agregarRect(datos, i, .910f, -.910f, .924f, .910f, blanco, blanco, blanco);
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
                // Acera clara alrededor de una calzada de asfalto oscuro.
                // Planos independientes: acera y calzada no comparten la cara superior del cubo.
                dibujarPlanoPavimento(x, .12f, z, 9.82f, 9.82f, .56f, .53f, .46f, .03f, .03f, .025f);
                dibujarPlanoPavimento(x, .18f, z, 8.92f, 8.92f, .08f, .085f, .085f, .075f, .078f, .075f);
                dibujarMarcasViales(x, z, fila, columna);
            } else if (tipo == 1) {
                if (fila == 4 && columna == 4) {
                    dibujarCatedralCruceña(x, z);
                    continue;
                }
                float alto = 9 + ((fila * 7 + columna * 5) % 4) * 4;
                float[][] fachadas = {{.60f, .31f, .18f}, {.77f, .66f, .48f}, {.72f, .70f, .62f}, {.49f, .28f, .20f}};
                float[] color = fachadas[(fila * 3 + columna) % fachadas.length];
                dibujarCubo(x, alto / 2, z, 8.5f, alto, 8.5f, 0, color[0], color[1], color[2], 0, 0, 0);
                dibujarCubo(x, alto + .25f, z, 8.7f, .5f, 8.7f, 0, .31f, .20f, .14f, 0, 0, 0);
                dibujarVentanasEdificio(x, z, alto);
            } else {
                dibujarCubo(x, -0.02f, z, 9.4f, .18f, 9.4f, 0, .08f, .28f, .10f, 0, 0, 0);
                if (fila == 1 && columna == 4) {
                    dibujarPlazaCristo(x, z);
                } else if ((fila == 4 && columna == 0) || (fila == 7 && columna == 7)) {
                    dibujarPlazaBanderas(x, z);
                } else {
                    dibujarArbol(x - 2.5f, z - 1.6f, 1.0f);
                    dibujarArbol(x + 2.3f, z + 1.7f, .8f);
                    dibujarBanco(x - 1.5f, z + 3.2f, 0);
                    dibujarBanco(x + 3.1f, z - 2.8f, 90);
                }
            }
        }
    }

    /** Catedral original de ladrillo y torres, inspirada en rasgos coloniales cruceños. */
    private void dibujarCatedralCruceña(float x, float z) {
        float ladrilloR = .61f, ladrilloG = .30f, ladrilloB = .16f;
        dibujarCubo(x, .24f, z, 8.70f, .48f, 8.70f, 0, .50f, .43f, .34f, 0, 0, 0);
        dibujarCubo(x, 4.15f, z, 3.95f, 7.85f, 5.90f, 0, ladrilloR, ladrilloG, ladrilloB, 0, 0, 0);
        for (float lateral : new float[]{-3.05f, 3.05f}) {
            dibujarCubo(x + lateral, 5.55f, z, 1.72f, 10.70f, 2.30f, 0, .57f, .27f, .14f, 0, 0, 0);
            dibujarCubo(x + lateral, 10.98f, z, 2.05f, .42f, 2.62f, 0, .42f, .20f, .12f, 0, 0, 0);
            dibujarCubo(x + lateral, 11.38f, z, .88f, .62f, .88f, 0, .76f, .49f, .24f, .04f, .02f, 0);
            dibujarCubo(x + lateral, 7.10f, z - 1.20f, .78f, 2.05f, .12f, 0, .08f, .10f, .12f, 0, 0, 0);
        }
        // Portada, arco central y un rosetón que reconocen la fachada desde la avenida.
        dibujarCubo(x, 1.70f, z - 3.02f, 1.48f, 3.00f, .16f, 0, .12f, .095f, .075f, 0, 0, 0);
        dibujarCubo(x, 4.70f, z - 3.04f, 1.56f, 1.75f, .15f, 0, .09f, .11f, .13f, 0, 0, 0);
        dibujarEsfera(x, 6.42f, z - 3.13f, .62f, .62f, .13f, .90f, .78f, .54f, .05f, .03f, .01f);
        dibujarCubo(x, 8.32f, z, 4.55f, .38f, 6.38f, 0, .49f, .22f, .13f, 0, 0, 0);
        dibujarCubo(x, 8.72f, z, 1.55f, .68f, 3.05f, 0, .64f, .32f, .17f, 0, 0, 0);
    }

    /** Plaza monumental original inspirada en el Cristo cruceño, construida solo con geometría propia. */
    private void dibujarPlazaCristo(float x, float z) {
        // Gradas bajas: el conjunto prioriza la figura, no una estructura de columnas.
        dibujarCubo(x, .13f, z, 8.10f, .26f, 8.10f, 0, .62f, .63f, .59f, 0, 0, 0);
        dibujarCubo(x, .40f, z, 5.85f, .30f, 5.85f, 0, .76f, .75f, .68f, 0, 0, 0);
        dibujarCubo(x, .80f, z, 3.70f, .52f, 3.70f, 0, .40f, .42f, .41f, 0, 0, 0);
        dibujarCubo(x, 1.35f, z, 2.35f, .66f, 2.05f, 0, .32f, .35f, .34f, 0, 0, 0);

        // Figura monumental: una túnica escalonada, cabeza redondeada y brazos formando una V clara.
        float piedraR = .22f, piedraG = .29f, piedraB = .28f;
        dibujarCubo(x, 2.85f, z, 1.60f, 2.45f, 1.02f, 0, piedraR, piedraG, piedraB, 0, 0, 0);
        dibujarCubo(x, 4.40f, z, 1.30f, 1.18f, .88f, 0, .25f, .32f, .31f, 0, 0, 0);
        dibujarEsfera(x, 5.35f, z, .47f, .55f, .47f, .40f, .43f, .39f, 0, 0, 0);
        // Cabello/capucha detrás de la cabeza para separar visualmente la silueta del cielo.
        dibujarCubo(x, 5.42f, z + .20f, .92f, .95f, .28f, 0, .12f, .17f, .17f, 0, 0, 0);
        // Hombros y brazos en escalones ascendentes: la lectura frontal es inequívocamente de brazos levantados.
        dibujarCubo(x - .83f, 4.76f, z, 1.05f, .38f, .72f, 0, piedraR, piedraG, piedraB, 0, 0, 0);
        dibujarCubo(x + .83f, 4.76f, z, 1.05f, .38f, .72f, 0, piedraR, piedraG, piedraB, 0, 0, 0);
        dibujarCubo(x - 1.38f, 5.14f, z, .60f, .42f, .62f, 0, piedraR, piedraG, piedraB, 0, 0, 0);
        dibujarCubo(x + 1.38f, 5.14f, z, .60f, .42f, .62f, 0, piedraR, piedraG, piedraB, 0, 0, 0);
        dibujarCubo(x - 1.68f, 5.55f, z, .34f, .65f, .54f, 0, .26f, .33f, .32f, 0, 0, 0);
        dibujarCubo(x + 1.68f, 5.55f, z, .34f, .65f, .54f, 0, .26f, .33f, .32f, 0, 0, 0);
        dibujarEsfera(x - 1.68f, 6.00f, z, .20f, .27f, .20f, .42f, .44f, .40f, 0, 0, 0);
        dibujarEsfera(x + 1.68f, 6.00f, z, .20f, .27f, .20f, .42f, .44f, .40f, 0, 0, 0);
        // Banderas laterales que acompañan el monumento sin ocultar su silueta.
        dibujarBanderaCruceña(x - 3.25f, z - 2.45f, 4.80f, .58f);
        dibujarBanderaCruceña(x + 3.25f, z + 2.45f, 4.80f, .58f);
        dibujarBanco(x - 3.15f, z + 3.15f, 0);
        dibujarBanco(x + 3.15f, z - 3.15f, 180);
    }

    /** Plaza cívica con tres banderas verde, blanco y verde sobre mástiles. */
    private void dibujarPlazaBanderas(float x, float z) {
        dibujarCubo(x, .13f, z, 8.10f, .26f, 8.10f, 0, .64f, .64f, .59f, 0, 0, 0);
        dibujarCubo(x, .35f, z, 5.90f, .24f, 5.90f, 0, .76f, .75f, .68f, 0, 0, 0);
        dibujarBanderaCruceña(x - 2.35f, z, 5.75f, .82f);
        dibujarBanderaCruceña(x, z, 6.85f, 1.0f);
        dibujarBanderaCruceña(x + 2.35f, z, 5.75f, .82f);
        dibujarBanco(x - 2.8f, z + 3.25f, 0);
        dibujarBanco(x + 2.8f, z - 3.25f, 180);
    }

    private void dibujarBanderaCruceña(float x, float z, float alto, float escala) {
        float ancho = 2.25f * escala, franja = .52f * escala;
        dibujarCubo(x, alto / 2, z, .12f, alto, .12f, 0, .18f, .19f, .20f, 0, 0, 0);
        dibujarCubo(x, alto + .06f, z, .44f, .15f, .44f, 0, .52f, .48f, .18f, .10f, .08f, .01f);
        float banderaX = x + ancho / 2;
        dibujarCubo(banderaX, alto - franja * .40f, z, ancho, franja, .09f, 0, .02f, .42f, .16f, 0, 0, 0);
        dibujarCubo(banderaX, alto - franja * 1.40f, z, ancho, franja, .09f, 0, .94f, .94f, .90f, 0, 0, 0);
        dibujarCubo(banderaX, alto - franja * 2.40f, z, ancho, franja, .09f, 0, .02f, .42f, .16f, 0, 0, 0);
    }

    private void dibujarMarcasViales(float x, float z, int fila, int columna) {
        // Diseño vial: doble línea amarilla central y líneas blancas en ambos bordes del asfalto.
        boolean ejeVertical = columna == 2 || columna == 5 || columna == 8;
        if (ejeVertical) {
            dibujarPlanoPavimento(x - .24f, .235f, z, .11f, 8.55f, .96f, .58f, .10f, 0, 0, 0);
            dibujarPlanoPavimento(x + .24f, .235f, z, .11f, 8.55f, .96f, .58f, .10f, 0, 0, 0);
            dibujarPlanoPavimento(x - 4.03f, .233f, z, .075f, 8.55f, .92f, .91f, .84f, 0, 0, 0);
            dibujarPlanoPavimento(x + 4.03f, .233f, z, .075f, 8.55f, .92f, .91f, .84f, 0, 0, 0);
        } else {
            dibujarPlanoPavimento(x, .235f, z - .24f, 8.55f, .11f, .96f, .58f, .10f, 0, 0, 0);
            dibujarPlanoPavimento(x, .235f, z + .24f, 8.55f, .11f, .96f, .58f, .10f, 0, 0, 0);
            dibujarPlanoPavimento(x, .233f, z - 4.03f, 8.55f, .075f, .92f, .91f, .84f, 0, 0, 0);
            dibujarPlanoPavimento(x, .233f, z + 4.03f, 8.55f, .075f, .92f, .91f, .84f, 0, 0, 0);
        }
    }

    private void dibujarVentanasEdificio(float x, float z, float alto) {
        float brillo = noche ? .92f : .08f;
        for (float y = 2.1f; y < alto - 1; y += 3.2f) {
            for (float desplazamiento : new float[]{-2.65f, 0, 2.65f}) {
                dibujarCubo(x + desplazamiento, y, z + 4.28f, 1.25f, 1.15f, .08f, 0, .10f, .32f, .36f, brillo * .25f, brillo * .62f, brillo * .72f);
                dibujarCubo(x + desplazamiento, y, z - 4.28f, 1.25f, 1.15f, .08f, 0, .10f, .32f, .36f, brillo * .25f, brillo * .62f, brillo * .72f);
                dibujarCubo(x + 4.28f, y, z + desplazamiento, .08f, 1.15f, 1.25f, 0, .10f, .32f, .36f, brillo * .25f, brillo * .62f, brillo * .72f);
                dibujarCubo(x - 4.28f, y, z + desplazamiento, .08f, 1.15f, 1.25f, 0, .10f, .32f, .36f, brillo * .25f, brillo * .62f, brillo * .72f);
            }
        }
    }

    /** Árbol urbano de copas compuestas, más legible que las palmeras a esta escala. */
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
                dibujarPlanoPavimento(cruce[0] + desplazamiento, .255f, cruce[1] - 3.8f, .34f, 1.25f, .78f, .78f, .72f, 0, 0, 0);
                dibujarPlanoPavimento(cruce[0] - 3.8f, .255f, cruce[1] + desplazamiento, 1.25f, .34f, .78f, .78f, .72f, 0, 0, 0);
            }
        }
    }

    private void dibujarSemaforos(float tiempo) {
        float fase = tiempo % 9.0f;
        int luzActiva = fase < 4.0f ? 0 : (fase < 7.0f ? 1 : 2); // rojo, verde, amarillo
        for (float[] esquina : INTERSECCIONES) {
            // Cada semáforo se apoya en la esquina de la acera del cruce.
            float x = esquina[0] + 4.15f, z = esquina[1] + 4.15f;
            dibujarCubo(x, 2.2f, z, .18f, 4.4f, .18f, 0, .09f, .09f, .10f, 0, 0, 0);
            dibujarCubo(x, 4.0f, z, .72f, 1.95f, .52f, 0, .06f, .065f, .07f, 0, 0, 0);
            dibujarLuzSemaforo(x, 4.6f, z + .29f, .92f, .04f, .03f, luzActiva == 0);
            dibujarLuzSemaforo(x, 4.0f, z + .29f, .95f, .63f, .03f, luzActiva == 2);
            dibujarLuzSemaforo(x, 3.4f, z + .29f, .04f, .88f, .12f, luzActiva == 1);
        }
    }

    private void dibujarLuzSemaforo(float x, float y, float z, float r, float g, float b, boolean encendida) {
        float intensidad = encendida ? 1.0f : .025f;
        dibujarCubo(x, y, z, .36f, .30f, .08f, 0, r, g, b, r * intensidad, g * intensidad, b * intensidad);
    }

    private void dibujarDestino(float tiempo) {
        float vaiven = (float) Math.sin(tiempo * 2.4f) * .12f;
        float pulso = .76f + .15f * (float) Math.sin(tiempo * 3.5f);
        // Halo de misión bajo el carrito: comunica que es un destino interactivo.
        dibujarCubo(destinoX, .035f, destinoZ, 4.6f * pulso, .07f, 4.6f * pulso, tiempo * 28, .98f, .48f, .08f, .55f, .17f, .01f);
        // Chasis azul, ruedas y manillar del carrito.
        dibujarCubo(destinoX, .48f + vaiven, destinoZ, 3.10f, .22f, 2.15f, 0, .04f, .23f, .62f, 0, 0, 0);
        dibujarCubo(destinoX, .78f + vaiven, destinoZ - .78f, 3.10f, .18f, .16f, 0, .06f, .30f, .74f, 0, 0, 0);
        dibujarCubo(destinoX, 1.05f + vaiven, destinoZ + 1.12f, .18f, 1.25f, .18f, 0, .05f, .28f, .70f, 0, 0, 0);
        dibujarCubo(destinoX, 1.62f + vaiven, destinoZ + 1.55f, 2.20f, .16f, .16f, 0, .05f, .28f, .70f, 0, 0, 0);
        for (float lateral : new float[]{-1.42f, 1.42f}) {
            dibujarCubo(destinoX + lateral, .42f + vaiven, destinoZ - .64f, .42f, .72f, .42f, 0, .025f, .03f, .04f, 0, 0, 0);
            dibujarCubo(destinoX + lateral, .42f + vaiven, destinoZ + .68f, .42f, .72f, .42f, 0, .025f, .03f, .04f, 0, 0, 0);
        }
        // Tanque amarillo de somó y detalles verdes originales.
        dibujarEsfera(destinoX, 2.05f + vaiven, destinoZ - .10f, 1.46f, 1.46f, 1.46f, .95f, .63f, .06f, .12f, .06f, 0);
        dibujarCubo(destinoX, 2.02f + vaiven, destinoZ - 1.46f, 2.05f, .23f, .10f, 0, .05f, .36f, .16f, 0, 0, 0);
        dibujarCubo(destinoX, 3.45f + vaiven, destinoZ - .10f, .72f, .16f, .72f, 0, .96f, .80f, .26f, .16f, .09f, .01f);
        // Vasos sobre el tanque.
        dibujarCubo(destinoX - .35f, 3.85f + vaiven, destinoZ - .10f, .34f, .72f, .34f, 0, .92f, .94f, .90f, 0, 0, 0);
        dibujarCubo(destinoX + .35f, 3.85f + vaiven, destinoZ - .10f, .34f, .72f, .34f, 0, .92f, .94f, .90f, 0, 0, 0);
    }

    private void dibujarMinibus() {
        float r = (float) Math.toRadians(vehiculo.getAngulo());
        float adelanteX = (float) Math.sin(r), adelanteZ = (float) Math.cos(r);
        float ladoX = (float) Math.cos(r), ladoZ = -(float) Math.sin(r);
        // Carrocería del minibús: blanco, franja azul y zócalo rojo.
        // Dos volúmenes escalonados: base ancha y cabina más angosta, para evitar una silueta de caja.
        dibujarCubo(vehiculo.getX(), .86f, vehiculo.getZ(), 3.16f, 1.48f, 7.24f, vehiculo.getAngulo(), .88f, .89f, .84f, 0, 0, 0);
        dibujarCubo(vehiculo.getX(), 1.67f, vehiculo.getZ(), 2.88f, .74f, 6.70f, vehiculo.getAngulo(), .91f, .92f, .88f, 0, 0, 0);
        dibujarCubo(vehiculo.getX(), 1.18f, vehiculo.getZ(), 3.16f, .28f, 7.26f, vehiculo.getAngulo(), .04f, .34f, .72f, 0, 0, 0);
        dibujarCubo(vehiculo.getX(), .52f, vehiculo.getZ(), 3.18f, .26f, 7.28f, vehiculo.getAngulo(), .72f, .06f, .07f, 0, 0, 0);
        dibujarCubo(vehiculo.getX(), 2.08f, vehiculo.getZ(), 2.72f, .18f, 6.28f, vehiculo.getAngulo(), .045f, .075f, .11f, 0, 0, 0);
        // Techo cerrado con plano propio: evita que el interior parezca hueco desde la cámara elevada.
        dibujarPlano(vehiculo.getX(), 2.20f, vehiculo.getZ(), 2.66f, 6.20f, vehiculo.getAngulo(), .91f, .90f, .82f, .03f, .03f, .02f);
        dibujarCubo(vehiculo.getX() - ladoX * 1.28f, 2.25f, vehiculo.getZ() - ladoZ * 1.28f, .09f, .10f, 5.88f, vehiculo.getAngulo(), .03f, .25f, .58f, 0, 0, 0);
        dibujarCubo(vehiculo.getX() + ladoX * 1.28f, 2.25f, vehiculo.getZ() + ladoZ * 1.28f, .09f, .10f, 5.88f, vehiculo.getAngulo(), .03f, .25f, .58f, 0, 0, 0);
        dibujarCubo(vehiculo.getX(), 2.31f, vehiculo.getZ() - adelanteZ * .70f, 1.08f, .18f, 1.18f, vehiculo.getAngulo(), .14f, .18f, .20f, 0, 0, 0);

        // Parabrisas delantero y rótulo de línea estilizado.
        float frenteX = vehiculo.getX() + adelanteX * 3.62f, frenteZ = vehiculo.getZ() + adelanteZ * 3.62f;
        dibujarCubo(frenteX, 1.70f, frenteZ, 2.72f, .82f, .10f, vehiculo.getAngulo(), .06f, .22f, .32f, .01f, .03f, .05f);
        dibujarCubo(frenteX, 2.20f, frenteZ, 1.22f, .23f, .12f, vehiculo.getAngulo(), .96f, .75f, .14f, .16f, .10f, .01f);

        // Parte trasera legible desde la cámara de conducción: luneta, ruta 72, luces y paragolpes.
        float atrasX = vehiculo.getX() - adelanteX * 3.62f, atrasZ = vehiculo.getZ() - adelanteZ * 3.62f;
        float cartelX = atrasX - adelanteX * .07f, cartelZ = atrasZ - adelanteZ * .07f;
        dibujarCubo(atrasX, 1.62f, atrasZ, 2.72f, .76f, .12f, vehiculo.getAngulo(), .045f, .16f, .24f, .01f, .025f, .04f);
        dibujarCubo(atrasX - adelanteX * .07f, 1.62f, atrasZ - adelanteZ * .07f, .11f, .78f, .08f, vehiculo.getAngulo(), .82f, .84f, .80f, 0, 0, 0);
        dibujarCubo(atrasX, 2.22f, atrasZ, 1.42f, .44f, .14f, vehiculo.getAngulo(), .025f, .035f, .05f, 0, 0, 0);
        // En la parte trasera se invierte la geometría y el orden para que se lea 72, no su reflejo.
        dibujarDigitoRuta(7, .37f, 2.22f, cartelX, cartelZ, ladoX, ladoZ, vehiculo.getAngulo(), true);
        dibujarDigitoRuta(2, -.37f, 2.22f, cartelX, cartelZ, ladoX, ladoZ, vehiculo.getAngulo(), true);
        dibujarCubo(atrasX, 1.10f, atrasZ, 3.20f, .26f, .14f, vehiculo.getAngulo(), .04f, .34f, .72f, 0, 0, 0);
        dibujarCubo(atrasX, .52f, atrasZ, 3.22f, .28f, .16f, vehiculo.getAngulo(), .72f, .06f, .07f, .03f, 0, 0);
        dibujarCubo(atrasX, .35f, atrasZ, 3.30f, .24f, .22f, vehiculo.getAngulo(), .10f, .12f, .15f, 0, 0, 0);
        for (float lateral : new float[]{-.98f, .98f}) {
            float luzX = atrasX + ladoX * lateral - adelanteX * .09f;
            float luzZ = atrasZ + ladoZ * lateral - adelanteZ * .09f;
            dibujarCubo(luzX, .83f, luzZ, .38f, .25f, .10f, vehiculo.getAngulo(), .88f, .035f, .025f, .22f, .004f, .002f);
        }

        // Ventanas laterales y puerta de acceso.
        for (float lateral : new float[]{-1.57f, 1.57f}) for (float longitudinal : new float[]{-2.15f, -1.02f, .12f, 1.26f, 2.35f}) {
            float x = vehiculo.getX() + ladoX * lateral + adelanteX * longitudinal;
            float z = vehiculo.getZ() + ladoZ * lateral + adelanteZ * longitudinal;
            dibujarCubo(x, 1.78f, z, .10f, .72f, .84f, vehiculo.getAngulo(), .05f, .19f, .31f, .01f, .02f, .04f);
        }
        float puertaX = vehiculo.getX() + ladoX * -1.58f + adelanteX * 1.62f;
        float puertaZ = vehiculo.getZ() + ladoZ * -1.58f + adelanteZ * 1.62f;
        dibujarCubo(puertaX, 1.08f, puertaZ, .12f, 1.50f, 1.05f, vehiculo.getAngulo(), .08f, .11f, .15f, 0, 0, 0);

        for (float lateral : new float[]{-1.58f, 1.58f}) for (float frontal : new float[]{-2.42f, 2.42f}) {
            float wx = vehiculo.getX() + (float) Math.cos(r) * lateral + (float) Math.sin(r) * frontal;
            float wz = vehiculo.getZ() - (float) Math.sin(r) * lateral + (float) Math.cos(r) * frontal;
            dibujarCubo(wx, .42f, wz, .52f, .78f, 1.02f, vehiculo.getAngulo(), .02f, .025f, .03f, 0, 0, 0);
            dibujarCubo(wx, .42f, wz, .20f, .40f, 1.08f, vehiculo.getAngulo(), .55f, .58f, .61f, 0, 0, 0);
        }
        float fx = vehiculo.getX() + adelanteX * 3.68f, fz = vehiculo.getZ() + adelanteZ * 3.68f;
        dibujarCubo(fx + ladoX * .86f, .88f, fz + ladoZ * .86f, .42f, .28f, .12f, vehiculo.getAngulo(), .94f,.94f,.76f, focos?1:.05f, focos?1:.05f, focos?0.55f:.02f);
        dibujarCubo(fx - ladoX * .86f, .88f, fz - ladoZ * .86f, .42f, .28f, .12f, vehiculo.getAngulo(), .94f,.94f,.76f, focos?1:.05f, focos?1:.05f, focos?0.55f:.02f);
    }

    /** Dibuja un dígito de siete segmentos en el cartel trasero del minibús. */
    private void dibujarDigitoRuta(int digito, float lateral, float centroY, float x, float z, float ladoX, float ladoZ, float angulo, boolean espejado) {
        boolean[] segmentos = switch (digito) {
            case 7 -> new boolean[]{true, true, true, false, false, false, false};
            case 2 -> new boolean[]{true, true, false, true, true, false, true};
            default -> new boolean[7];
        };
        // Una cara trasera se observa desde el sentido opuesto al frente del vehículo.
        if (espejado) {
            boolean temporal = segmentos[1]; segmentos[1] = segmentos[5]; segmentos[5] = temporal;
            temporal = segmentos[2]; segmentos[2] = segmentos[4]; segmentos[4] = temporal;
        }
        float[] alturaHorizontal = {.23f, 0, -.23f};
        if (segmentos[0]) dibujarSegmentoRuta(x, z, lateral, centroY + alturaHorizontal[0], true, ladoX, ladoZ, angulo);
        if (segmentos[1]) dibujarSegmentoRuta(x, z, lateral + .18f, centroY + .115f, false, ladoX, ladoZ, angulo);
        if (segmentos[2]) dibujarSegmentoRuta(x, z, lateral + .18f, centroY - .115f, false, ladoX, ladoZ, angulo);
        if (segmentos[3]) dibujarSegmentoRuta(x, z, lateral, centroY - alturaHorizontal[0], true, ladoX, ladoZ, angulo);
        if (segmentos[4]) dibujarSegmentoRuta(x, z, lateral - .18f, centroY - .115f, false, ladoX, ladoZ, angulo);
        if (segmentos[5]) dibujarSegmentoRuta(x, z, lateral - .18f, centroY + .115f, false, ladoX, ladoZ, angulo);
        if (segmentos[6]) dibujarSegmentoRuta(x, z, lateral, centroY, true, ladoX, ladoZ, angulo);
    }

    private void dibujarSegmentoRuta(float baseX, float baseZ, float lateral, float y, boolean horizontal, float ladoX, float ladoZ, float angulo) {
        float x = baseX + ladoX * lateral, z = baseZ + ladoZ * lateral;
        dibujarCubo(x, y, z, horizontal ? .28f : .065f, horizontal ? .055f : .22f, .07f, angulo, .98f, .76f, .14f, .22f, .12f, .01f);
    }

    private void actualizarFocos() {
        float r = (float) Math.toRadians(vehiculo.getAngulo()), adelanteX = (float) Math.sin(r), adelanteZ = (float) Math.cos(r), ladoX = (float) Math.cos(r), ladoZ = -(float) Math.sin(r);
        for (int i = 0; i < 2; i++) {
            float lado = i == 0 ? -.86f : .86f;
            posicionFocos[i * 3] = vehiculo.getX() + adelanteX * 3.68f + ladoX * lado;
            posicionFocos[i * 3 + 1] = .88f;
            posicionFocos[i * 3 + 2] = vehiculo.getZ() + adelanteZ * 3.68f + ladoZ * lado;
            direccionFocos[i * 3] = adelanteX; direccionFocos[i * 3 + 1] = -.08f; direccionFocos[i * 3 + 2] = adelanteZ;
        }
    }

    private void dibujarCubo(float x, float y, float z, float sx, float sy, float sz, float angulo, float r, float g, float b, float er, float eg, float eb) {
        float[] modelo = modelo(x, y, z, sx / 2, sy / 2, sz / 2, angulo);
        GL20.glUniformMatrix4fv(uModelo, false, modelo); GL20.glUniform3f(uColor, r, g, b); GL20.glUniform3f(uEmision, er, eg, eb);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
    }

    private void dibujarPlanoPavimento(float x, float y, float z, float sx, float sz, float r, float g, float b, float er, float eg, float eb) {
        dibujarPlano(x, y, z, sx, sz, 0, r, g, b, er, eg, eb);
    }

    private void dibujarPlano(float x, float y, float z, float sx, float sz, float angulo, float r, float g, float b, float er, float eg, float eb) {
        float[] modelo = modelo(x, y, z, sx / 2, 1, sz / 2, angulo);
        GL20.glUniformMatrix4fv(uModelo, false, modelo); GL20.glUniform3f(uColor, r, g, b); GL20.glUniform3f(uEmision, er, eg, eb);
        GL30.glBindVertexArray(vaoPlano);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(vaoCubo);
    }

    private void dibujarEsfera(float x, float y, float z, float sx, float sy, float sz, float r, float g, float b, float er, float eg, float eb) {
        float[] modelo = modelo(x, y, z, sx, sy, sz, 0);
        GL20.glUniformMatrix4fv(uModelo, false, modelo); GL20.glUniform3f(uColor, r, g, b); GL20.glUniform3f(uEmision, er, eg, eb);
        GL30.glBindVertexArray(vaoEsfera);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verticesEsfera);
        GL30.glBindVertexArray(vaoCubo);
    }

    private void liberar() {
        GL20.glDeleteProgram(programa); GL20.glDeleteProgram(programaMapa);
        GL15.glDeleteBuffers(vboCubo); GL15.glDeleteBuffers(vboPlano); GL15.glDeleteBuffers(vboEsfera); GL15.glDeleteBuffers(vboMapa);
        GL30.glDeleteVertexArrays(vaoCubo); GL30.glDeleteVertexArrays(vaoPlano); GL30.glDeleteVertexArrays(vaoEsfera); GL30.glDeleteVertexArrays(vaoMapa);
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
