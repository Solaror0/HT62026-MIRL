/*
 * ESP-NOW Gateway (Receiver)
 * Compiles on both ESP8266 and ESP32 without modification.
 * Receives data from multiple ESP32/ESP8266 sender nodes and prints to Serial.
 */

#if defined(ESP8266)
#include <ESP8266WiFi.h>
#include <espnow.h>
#else
#include <WiFi.h>
#include <esp_now.h>
#endif


// 1. Define a data structure to hold the incoming data.
// IMPORTANT: This struct MUST exactly match the struct on your sender ESPs!
typedef struct struct_message {
  int node_id;     // E.g., 1, 2, 3... to identify which ESP is sending
  float transmission;  // Example sensor data
} struct_message;

struct_message myData;

// --- ESP8266 Receive Callback ---
#if defined(ESP8266)
void OnDataRecv(uint8_t * mac, uint8_t *incomingData, uint8_t len) {
  memcpy(&myData, incomingData, sizeof(myData));
  
  // Format and upload data to PC via Serial
  Serial.print("MAC: ");
  for (int i = 0; i < 6; i++) {
    Serial.printf("%02X", mac[i]);
    if (i < 5) Serial.print(":");
  }
  Serial.print(" | Node ID: "); Serial.print(myData.node_id);
  Serial.print(" | Sensor 1: "); Serial.print(myData.transmission);

}

// --- ESP32 Receive Callback ---
#elif defined(ESP32)
// Compatibility wrapper: The ESP32 Arduino Core v3.0 changed the callback signature
#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 0, 0)
void OnDataRecv(const esp_now_recv_info_t *esp_now_info, const uint8_t *incomingData, int len) {
  const uint8_t *mac = esp_now_info->src_addr;
#else
void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
#endif
  memcpy(&myData, incomingData, sizeof(myData));
  
  // Format and upload data to PC via Serial
  Serial.print("MAC: ");
  for (int i = 0; i < 6; i++) {
    Serial.printf("%02X", mac[i]);
    if (i < 5) Serial.print(":");
  }
  Serial.print(" | Node ID: "); Serial.print(myData.node_id);
  Serial.print(" | Sensor 1: "); Serial.print(myData.sensor_1);
  Serial.print(" | Sensor 2: "); Serial.println(myData.sensor_2);
}
#endif

void setup() {
  Serial.begin(115200);
  
  // 2. Set device as a Wi-Fi Station
  WiFi.mode(WIFI_STA);
  // Disconnect from any saved Wi-Fi networks (ESP-NOW doesn't use a router)
  WiFi.disconnect();

  Serial.println("\n--- ESP-NOW Gateway Booting ---");
  Serial.print("Gateway MAC Address: ");
  Serial.println(WiFi.macAddress());
  Serial.println("IMPORTANT: Put this MAC address into all your Sender ESP32 codes!\n");

  // 3. Initialize ESP-NOW
#if defined(ESP8266)
  if (esp_now_init() != 0) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }
  esp_now_set_self_role(ESP_NOW_ROLE_SLAVE); // 8266 requires setting the role to Receiver (Slave)
#elif defined(ESP32)
  if (esp_now_init() != ESP_OK) {
    Serial.println("Error initializing ESP-NOW");
    return;
  }
#endif
  
  // 4. Register the receive callback function
  esp_now_register_recv_cb(OnDataRecv);
  
  Serial.println("ESP-NOW Initialized. Listening for data from sender nodes...");
}

void loop() {
  // ESP-NOW uses interrupts. When data arrives, it triggers the callback automatically.
  // You can leave the loop empty or do other non-blocking tasks here.
  delay(1000);
}