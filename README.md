<p align="center">
  <img src="assets/ciudad-santa-cruz-hero.png" alt="Vista ilustrada de Ciudad Interactiva OpenGL inspirada en Santa Cruz de la Sierra" width="100%">
</p>

<h1 align="center">🌃 Ciudad Interactiva OpenGL</h1>

<p align="center">
  <strong>Conduce. Explora. Completa entregas.</strong><br>
  Una ciudad 3D construida desde cero con Java, LWJGL y OpenGL.
</p>

<p align="center">
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white">
  <img alt="LWJGL 3.3.3" src="https://img.shields.io/badge/LWJGL-3.3.3-0B3D91?style=for-the-badge">
  <img alt="OpenGL 3.3" src="https://img.shields.io/badge/OpenGL-3.3-5586A4?style=for-the-badge&logo=opengl&logoColor=white">
  <img alt="Maven" src="https://img.shields.io/badge/Maven-Build-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white">
</p>

<p align="center">
  <a href="#-inicio-rápido">Inicio rápido</a> ·
  <a href="#-controles">Controles</a> ·
  <a href="#-experiencia-urbana">Funciones</a> ·
  <a href="#-tráfico-autónomo-e-interfaz">Tráfico e interfaz</a> ·
  <a href="#-arquitectura">Arquitectura</a>
</p>

---

> [!IMPORTANT]
> **Misión activa:** encuentra el carrito de somó, llega con el minibús y recibe un nuevo destino. Todo mientras esquivas edificios, exploras parques y eliges entre la ciudad de día o de noche.

## ✨ ¿Qué hace especial a esta ciudad?

| 🌆 Mundo vivo | 💡 Luz que cambia | 🚦 Tráfico y entregas |
| :--- | :--- | :--- |
| 11 × 11 celdas conectadas, edificios de alturas variables, parques y pasos peatonales. | Día/noche contrastado, farolas con atenuación, faros direccionales y ventanas cálidas. | Tres vehículos autónomos, carrito de somó 3D, destino aleatorio y contador de entregas. |

```mermaid
flowchart LR
    A[⌨️ Conducción] --> B{🚧 Colisión urbana}
    B -->|Camino libre| C[🚗 Ciudad 3D]
    B -->|Edificio o límite| A
    C --> D[🎯 Carrito de somó]
    D -->|Llegada| E[✅ Nueva misión]
    C --> F[💡 Iluminación GLSL]
    C --> G[🗺️ Minimapa y ruta GPS]
    C --> H[🚗 Tráfico autónomo]
```

## 🚀 Inicio rápido

### 1. Requisitos

| Herramienta | Versión |
| --- | --- |
| Java JDK | 17 |
| Apache Maven | 3.9+ |
| GPU / driver | Compatible con OpenGL 3.3 |

### 2. Ejecutar

```powershell
git clone https://github.com/alecaballero17/ciudad-interactiva-opengl.git
cd ciudad-interactiva-opengl
mvn clean compile
mvn exec:java
```

> En Windows, el `pom.xml` ya incluye los binarios nativos de LWJGL requeridos para ejecutar la aplicación.

## 🎮 Controles

<table align="center">
  <tr><th>Tecla</th><th>Acción</th></tr>
  <tr><td><code>W</code> / <code>S</code></td><td>Avanzar / retroceder</td></tr>
  <tr><td><code>A</code> / <code>D</code></td><td>Girar el minibús</td></tr>
  <tr><td><code>R</code></td><td>Reiniciar posición y orientación</td></tr>
  <tr><td><code>C</code></td><td>Alternar tercera persona / cámara orbital</td></tr>
  <tr><td><code>N</code></td><td>Alternar día / noche</td></tr>
  <tr><td><code>F</code></td><td>Encender o apagar faros</td></tr>
  <tr><td><code>M</code></td><td>Mostrar u ocultar minimapa</td></tr>
  <tr><td><code>Esc</code></td><td>Salir</td></tr>
</table>

## 🌇 Experiencia urbana

### 🏙️ Ciudad y conducción

- **Mapa expandido:** cuadrícula de 11 × 11 celdas, más de 12 manzanas construidas y múltiples parques conectados por calles.
- **Minibús Línea 72:** inspirado en el transporte urbano cruceño, con techo cerrado, franjas azul y roja, luces, parabrisas y señal de ruta visible.
- **Conducción estable:** aceleración, frenado, reversa limitada y movimiento dependiente de `delta time`. La colisión se resuelve con tres apoyos circulares y deslizamiento por ejes para evitar atascos en esquinas.
- **Dos cámaras:** una cámara elevada en tercera persona con suavizado y ocultamiento temporal de manzanas que bloquean la vista, más una cámara orbital para apreciar el escenario completo.

### 💡 Iluminación que transforma la escena

```text
☀️ Día  → luz ambiental + direccional
🌙 Noche → ambiente oscuro + farolas cálidas + ventanas iluminadas + faros del minibús
```

- VBO de cubo completo: **36 vértices**, posiciones `vec3` y normales unitarias `vec3`.
- Cálculo de luz difusa en GLSL a partir de las normales reales de cada superficie.
- Nueve farolas con atenuación cuadrática, dos focos delanteros orientados con el vehículo y un balance nocturno que conserva contraste visual.

### 🌳 Detalles que le dan vida

| Parques | Edificios | Intersecciones |
| --- | --- | --- |
| Árboles con troncos y copas compuestas de cubos; bancos de madera. | Fachadas variables, techos y ventanas brillantes al anochecer. | Pasos peatonales y semáforos con ciclo rojo → verde → amarillo. |

- **Identidad cruceña:** una plaza monumental con una estatua original inspirada en el Cristo y dos plazas cívicas con banderas verde, blanco y verde.
- **Paleta local:** fachadas de ladrillo terracota, crema y estilo colonial; árboles urbanos y una catedral original como hito visual.

### 🚗 Tráfico autónomo e interfaz

- **Tres vehículos autónomos:** circulan continuamente por circuitos cerrados sobre la red vial, actualizan su velocidad, posición y orientación, y realizan giros en intersecciones sin atravesar edificios ni salir del mapa.
- **Tablero de conducción:** interfaz con tipografía legible que muestra velocidad, estado de los faros, modo día/noche y un recordatorio de controles.
- **Presentación verificable:** el minimapa incluye marcadores de colores para los vehículos autónomos, además de la ruta GPS amarilla, el minibús y el destino activo.

### 🗺️ Minimapa y misiones

- Segunda pasada de render mediante `glViewport` y proyección ortográfica.
- Vista superior con norte hacia arriba, ciudad completa, marcador de orientación del minibús, vehículos autónomos y destino actual.
- Carrito de somó flotante y animado: al alcanzarlo, se genera una nueva misión en una calle transitable.

## 🧩 Arquitectura

```text
src/main/java/com/graphics/AppCiudad.java
│
├── Inicialización GLFW + OpenGL
├── VAO / VBO reutilizable para todos los cubos
├── Shaders GLSL: iluminación, emisión y focos
├── Matriz urbana, edificios y colisiones circulares
├── Minibús, tráfico autónomo, cámaras y entrada por teclado
├── Decoración: parques, bancos, farolas y semáforos
└── Tablero de interfaz + vista principal + minimapa
```

| Componente | Decisión de diseño |
| --- | --- |
| Geometría | Todos los objetos se componen de cubos transformados con su matriz de modelo. |
| Colisiones | Tres apoyos circulares del minibús frente a edificios y límites urbanos, con deslizamiento por ejes. |
| Materiales | Uniformes de color y emisión; las ventanas incrementan brillo de noche. |
| Iluminación | Luz ambiental y direccional, 9 farolas, 2 focos tipo spot y emisión moderada de ventanas nocturnas. |
| Misiones | Selección aleatoria de una celda de calle y comprobación de distancia radial. |
| Tráfico | Tres vehículos con rutas cerradas, velocidad constante y giros automáticos sobre calles. |
| Interfaz | Tablero de conducción renderizado como textura dinámica con tipografía normal. |

## 🗂️ Estructura del repositorio

```text
ciudad-interactiva-opengl/
├── assets/
│   └── ciudad-santa-cruz-hero.png    # Ilustración de portada
├── src/main/java/com/graphics/
│   ├── AppCiudad.java                # Ciudad, render, luces y misiones
│   └── Vehiculo.java                 # Estado y movimiento del minibús
├── .gitignore
├── DocumentacionProyectoCiudad_Completa.docx
├── pom.xml
└── README.md
```

<details>
<summary><strong>⚠️ Limitaciones conocidas</strong></summary>
<br>

- Los semáforos tienen alcance visual, por lo que no detienen al minibús.
- El tráfico autónomo es demostrativo: no colisiona con el minibús para no bloquear el recorrido del jugador.
- La ciudad se construye proceduralmente con cubos y shaders; la única textura dinámica es el tablero de interfaz generado durante la ejecución.
</details>

## 👤 Autor

<p align="center">
  <strong>Alejandro Caballero</strong><br>
  Proyecto académico · Programación Gráfica
</p>

<p align="center">
  <sub>Hecho con ☕, Java y OpenGL.</sub>
</p>
