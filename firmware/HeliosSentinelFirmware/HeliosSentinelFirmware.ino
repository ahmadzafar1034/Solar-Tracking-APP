/*
 * Helios Sentinel DS2001 — ESP32-S3 firmware
 * -------------------------------------------
 * Dual-axis solar tracker reference firmware that matches the REST API
 * used by the Helios Sentinel companion Android app.
 *
 * Hardware assumptions (change the pins to match your build):
 *   - 4x LDR light sensors (top-left, top-right, bottom-left, bottom-right)
 *     wired as voltage dividers into 4 ADC1-capable GPIOs.
 *   - 2x 180° hobby servos: pan (X axis) and tilt (Y axis), via ESP32Servo.
 *
 * Required library (Arduino IDE > Library Manager):  ESP32Servo
 * WiFi.h, WebServer.h, ESPmDNS.h and Preferences.h ship with the ESP32 core.
 *
 * HTTP API (consumed by the Android app):
 *   GET  /api/status  -> { "mode":"auto"|"manual", "angleX":F, "angleY":F,
 *                          "ldrTopLeft":I, "ldrTopRight":I, "ldrBottomLeft":I,
 *                          "ldrBottomRight":I, "uptimeMs":I }
 *   POST /api/control  { "mode":"auto" }  or  { "mode":"manual", "angleX":F, "angleY":F }
 *                      -> responds with the updated status
 *   GET  /api/config  -> { "ldrThreshold":I, "pollIntervalMs":I, "minAngle":F, "maxAngle":F }
 *   POST /api/config   any subset of those fields -> responds with the saved config
 *                      (saved to flash, so it survives a reboot)
 *
 * The board also announces itself on the network as "helios-sentinel.local"
 * (mDNS service "_helios._tcp"), so the app's "Find tracker" button works.
 */

#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <ESP32Servo.h>

// ---------- WiFi ----------
// By default the board starts its own hotspot so the phone can connect in the
// field. Set USE_STATION_MODE to true and fill in your router's details to join
// an existing network instead (the IP is printed on the Serial Monitor).
#define USE_STATION_MODE false
const char* AP_SSID = "HeliosSentinel";
const char* AP_PASSWORD = "sentinel123"; // WiFi AP mode needs 8+ characters
const char* STATION_SSID = "YOUR_WIFI_SSID";
const char* STATION_PASSWORD = "YOUR_WIFI_PASSWORD";
const char* MDNS_NAME = "helios-sentinel";

// ---------- Pins (adjust to your wiring) ----------
const int PIN_LDR_TOP_LEFT = 1;
const int PIN_LDR_TOP_RIGHT = 2;
const int PIN_LDR_BOTTOM_LEFT = 3;
const int PIN_LDR_BOTTOM_RIGHT = 4;
const int PIN_SERVO_PAN = 5;  // X axis
const int PIN_SERVO_TILT = 6; // Y axis

// If the panel moves AWAY from the light, flip the matching flag.
const bool INVERT_PAN = false;
const bool INVERT_TILT = false;

// ---------- Tracking tuning ----------
const unsigned long TRACK_INTERVAL_MS = 200; // auto-tracking update cadence
const int ADC_SAMPLES = 8;                   // readings averaged per sensor to cut noise
const int DARK_TOTAL = 60;                   // below this total light, hold position (night)
const float MIN_STEP_DEG = 0.5f;
const float MAX_STEP_DEG = 3.0f;

// ---------- State ----------
WebServer server(80);
Servo panServo;
Servo tiltServo;
Preferences prefs;

bool manualMode = false;
float angleX = 90.0f; // pan
float angleY = 90.0f; // tilt

int ldrThreshold = 40;               // minimum LDR difference before moving
unsigned long pollIntervalMs = 2000; // informational; the app decides how often it polls
float minAngle = 0.0f;
float maxAngle = 180.0f;

unsigned long lastTrackMs = 0;

// ---------- Helpers ----------
float clampAngle(float value) {
  if (value < minAngle) return minAngle;
  if (value > maxAngle) return maxAngle;
  return value;
}

void applyServoAngles() {
  panServo.write((int)(clampAngle(angleX) + 0.5f));
  tiltServo.write((int)(clampAngle(angleY) + 0.5f));
}

int readLdr(int pin) {
  long sum = 0;
  for (int i = 0; i < ADC_SAMPLES; i++) sum += analogRead(pin);
  return (int)(sum / ADC_SAMPLES);
}

void sendJson(int code, const String& json) {
  server.sendHeader("Access-Control-Allow-Origin", "*");
  server.send(code, "application/json", json);
}

void sendError(int code, const String& message) {
  sendJson(code, "{\"error\":\"" + message + "\"}");
}

// Minimal JSON field extraction for the flat objects the app sends, so we
// don't need the ArduinoJson library. Tolerates spaces around ':'.
bool extractString(const String& body, const String& key, String& out) {
  int keyIndex = body.indexOf("\"" + key + "\"");
  if (keyIndex < 0) return false;
  int colon = body.indexOf(':', keyIndex);
  if (colon < 0) return false;
  int firstQuote = body.indexOf('"', colon + 1);
  if (firstQuote < 0) return false;
  int secondQuote = body.indexOf('"', firstQuote + 1);
  if (secondQuote < 0) return false;
  out = body.substring(firstQuote + 1, secondQuote);
  return true;
}

bool extractNumber(const String& body, const String& key, float& out) {
  int keyIndex = body.indexOf("\"" + key + "\"");
  if (keyIndex < 0) return false;
  int colon = body.indexOf(':', keyIndex);
  if (colon < 0) return false;
  int start = colon + 1;
  while (start < (int)body.length() && body[start] == ' ') start++;
  int end = start;
  while (end < (int)body.length()) {
    char c = body[end];
    if (isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') end++;
    else break;
  }
  if (end == start) return false;
  out = body.substring(start, end).toFloat();
  return true;
}

// ---------- Persistence ----------
void loadConfig() {
  prefs.begin("helios", true);
  ldrThreshold = prefs.getInt("thr", ldrThreshold);
  pollIntervalMs = prefs.getULong("poll", pollIntervalMs);
  minAngle = prefs.getFloat("min", minAngle);
  maxAngle = prefs.getFloat("max", maxAngle);
  prefs.end();
  if (!(minAngle >= 0 && maxAngle <= 180 && minAngle < maxAngle)) { // corrupted / invalid -> defaults
    minAngle = 0.0f;
    maxAngle = 180.0f;
  }
}

void saveConfig() {
  prefs.begin("helios", false);
  prefs.putInt("thr", ldrThreshold);
  prefs.putULong("poll", pollIntervalMs);
  prefs.putFloat("min", minAngle);
  prefs.putFloat("max", maxAngle);
  prefs.end();
}

// ---------- HTTP handlers ----------
String statusJson() {
  String json = "{";
  json += "\"mode\":\"" + String(manualMode ? "manual" : "auto") + "\",";
  json += "\"angleX\":" + String(angleX, 1) + ",";
  json += "\"angleY\":" + String(angleY, 1) + ",";
  json += "\"ldrTopLeft\":" + String(readLdr(PIN_LDR_TOP_LEFT)) + ",";
  json += "\"ldrTopRight\":" + String(readLdr(PIN_LDR_TOP_RIGHT)) + ",";
  json += "\"ldrBottomLeft\":" + String(readLdr(PIN_LDR_BOTTOM_LEFT)) + ",";
  json += "\"ldrBottomRight\":" + String(readLdr(PIN_LDR_BOTTOM_RIGHT)) + ",";
  json += "\"uptimeMs\":" + String(millis());
  json += "}";
  return json;
}

String configJson() {
  String json = "{";
  json += "\"ldrThreshold\":" + String(ldrThreshold) + ",";
  json += "\"pollIntervalMs\":" + String(pollIntervalMs) + ",";
  json += "\"minAngle\":" + String(minAngle, 1) + ",";
  json += "\"maxAngle\":" + String(maxAngle, 1);
  json += "}";
  return json;
}

void handleGetStatus() {
  sendJson(200, statusJson());
}

void handlePostControl() {
  if (!server.hasArg("plain")) {
    sendError(400, "missing body");
    return;
  }
  String body = server.arg("plain");

  String mode;
  if (extractString(body, "mode", mode)) {
    if (mode == "manual") manualMode = true;
    else if (mode == "auto") manualMode = false;
    else {
      sendError(400, "mode must be auto or manual");
      return;
    }
  }

  if (manualMode) {
    float x, y;
    if (extractNumber(body, "angleX", x)) angleX = clampAngle(x);
    if (extractNumber(body, "angleY", y)) angleY = clampAngle(y);
    applyServoAngles();
  }
  sendJson(200, statusJson());
}

void handleGetConfig() {
  sendJson(200, configJson());
}

void handlePostConfig() {
  if (!server.hasArg("plain")) {
    sendError(400, "missing body");
    return;
  }
  String body = server.arg("plain");

  // Validate everything first, then apply, so a bad request changes nothing.
  float threshold = ldrThreshold, poll = pollIntervalMs, newMin = minAngle, newMax = maxAngle;
  extractNumber(body, "ldrThreshold", threshold);
  extractNumber(body, "pollIntervalMs", poll);
  extractNumber(body, "minAngle", newMin);
  extractNumber(body, "maxAngle", newMax);

  if (threshold < 0 || threshold > 4095) {
    sendError(400, "ldrThreshold must be 0-4095");
    return;
  }
  if (newMin < 0 || newMax > 180 || newMin >= newMax) {
    sendError(400, "angles must satisfy 0 <= minAngle < maxAngle <= 180");
    return;
  }
  if (poll < 100) poll = 100;

  ldrThreshold = (int)threshold;
  pollIntervalMs = (unsigned long)poll;
  minAngle = newMin;
  maxAngle = newMax;
  saveConfig();

  angleX = clampAngle(angleX); // keep the servos inside the new limits
  angleY = clampAngle(angleY);
  applyServoAngles();

  sendJson(200, configJson());
}

void handleNotFound() {
  sendError(404, "not found");
}

// ---------- Auto-tracking ----------
// Moves each axis toward the brighter side. The step grows with the size of
// the imbalance, so the panel catches up fast and then settles gently.
float stepFor(int difference) {
  int threshold = ldrThreshold > 0 ? ldrThreshold : 1;
  float step = MIN_STEP_DEG + (float)abs(difference) / (threshold * 4.0f);
  return step > MAX_STEP_DEG ? MAX_STEP_DEG : step;
}

void runAutoTracking() {
  if (manualMode) return;
  if (millis() - lastTrackMs < TRACK_INTERVAL_MS) return;
  lastTrackMs = millis();

  int tl = readLdr(PIN_LDR_TOP_LEFT);
  int tr = readLdr(PIN_LDR_TOP_RIGHT);
  int bl = readLdr(PIN_LDR_BOTTOM_LEFT);
  int br = readLdr(PIN_LDR_BOTTOM_RIGHT);
  if (tl + tr + bl + br < DARK_TOTAL) return; // night: hold position instead of wandering

  int horizontal = (tr + br) - (tl + bl); // > 0: brighter on the right
  int vertical = (tl + tr) - (bl + br);   // > 0: brighter on top

  if (abs(horizontal) > ldrThreshold) {
    float dir = horizontal > 0 ? 1.0f : -1.0f;
    if (INVERT_PAN) dir = -dir;
    angleX += dir * stepFor(horizontal);
  }
  if (abs(vertical) > ldrThreshold) {
    float dir = vertical > 0 ? 1.0f : -1.0f;
    if (INVERT_TILT) dir = -dir;
    angleY += dir * stepFor(vertical);
  }

  angleX = clampAngle(angleX);
  angleY = clampAngle(angleY);
  applyServoAngles();
}

// ---------- Setup / loop ----------
void setup() {
  Serial.begin(115200);
  loadConfig();

  panServo.attach(PIN_SERVO_PAN);
  tiltServo.attach(PIN_SERVO_TILT);
  applyServoAngles();

  if (USE_STATION_MODE) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(STATION_SSID, STATION_PASSWORD);
    Serial.print("Connecting to WiFi");
    while (WiFi.status() != WL_CONNECTED) {
      delay(300);
      Serial.print(".");
    }
    Serial.println();
    Serial.print("Connected. IP address: ");
    Serial.println(WiFi.localIP());
  } else {
    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID, AP_PASSWORD);
    Serial.print("Hotspot started. Join WiFi \"");
    Serial.print(AP_SSID);
    Serial.print("\" — tracker is at ");
    Serial.println(WiFi.softAPIP());
  }

  if (MDNS.begin(MDNS_NAME)) {
    MDNS.addService("helios", "tcp", 80);
    MDNS.addService("http", "tcp", 80);
    Serial.println("mDNS: helios-sentinel.local");
  }

  server.on("/api/status", HTTP_GET, handleGetStatus);
  server.on("/api/control", HTTP_POST, handlePostControl);
  server.on("/api/config", HTTP_GET, handleGetConfig);
  server.on("/api/config", HTTP_POST, handlePostConfig);
  server.onNotFound(handleNotFound);
  server.begin();
  Serial.println("HTTP server started.");
}

void loop() {
  server.handleClient();
  runAutoTracking();
}
