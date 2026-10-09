# RutaCosto 🇨🇴
Aplicación Android para estimar costos de viaje por carretera en Colombia.

## Versión 0.3.0
- Completa el origen con la ubicación del teléfono.
- Busca ciudades y lugares en Colombia mediante geocodificación de HeiGIT/Pelias.
- Calcula opciones de ruta con preferencias rápida, recomendada y menor distancia.
- Estima combustible con consumo (km/galón) y precio por galón definidos por el usuario.
- Muestra indicaciones principales y duración estimada.
- No inventa tarifas de peajes: por ahora las marca como no incluidas y deben verificarse por separado.

## Configurar la clave gratuita de HeiGIT
1. Entra en https://account.heigit.org/ y crea una cuenta o inicia sesión.
2. Genera o copia tu clave API de HeiGIT.
3. En GitHub abre el repositorio, luego **Settings → Secrets and variables → Actions**.
4. Crea un repository secret llamado `ORS_API_KEY` y pega allí la clave. No publiques la clave en el código ni en mensajes.
5. Cada cambio guardado en la rama `main` inicia una compilación automática. También se puede iniciar desde **Actions → Android build → Run workflow**.
6. Descarga el artefacto `RutaCosto-debug` cuando el workflow termine correctamente.

La API tiene cuotas y límites; revisa las condiciones vigentes en https://openrouteservice.org/restrictions/ y https://giscience.github.io/openrouteservice/frequently-asked-questions.html.

**Seguridad:** una clave incorporada en una APK puede extraerse. Para distribuir RutaCosto a otras personas, lo recomendable es mover las llamadas de API a un servidor intermedio con límites, en vez de incluir la clave directamente en la app.

## Funciones que aún faltan
1. Base de tarifas oficiales y vigentes de peajes en Colombia.
2. Mapa visual con el trazado completo de cada ruta.
3. Historial de viajes y aprendizaje de costos reales.
4. Validación de rutas en trayectos urbanos y de larga distancia.
