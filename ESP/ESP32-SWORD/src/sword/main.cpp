/*
 * ESP-NOW Sender (Node)
 * Updated to include node_id and transmission
 */

#if defined(ESP8266)
  #include <ESP8266WiFi.h>
  #include <espnow.h>
#elif defined(ESP32)
  #include <WiFi.h>
  #include <esp_now.h>
#endif

// The MAC address of the receiver (Gateway)
uint8_t broadcastAddress[] = {0xEC, 0xFA, 0xBC, 0x4B, 0x3F, 0x93};

// Define the updated data structure
typedef struct struct_message {
  int node_id;
  float transmission;
} struct_message;

struct_message myData;

// --- Callback to confirm if the packet actually reached the receiver ---
#if defined(ESP8266)
void OnDataSent(uint8_t *mac_addr, uint8_t sendStatus) {
  Serial.print("Last Packet Send Status: ");
  Serial.println(sendStatus == 0 ? "Delivery Success" : "Delivery Fail");
}
#elif defined(ESP32)
void OnDataSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  Serial.print("Last Packet Send Status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "Delivery Success" : "Delivery Fail");
}
#endif

void setup() {
  Serial.begin(115200);

  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  Serial.println("\n--- ESP-NOW Sender Booting ---");

#if defined(ESP8266)
  if (esp_now_init() != 0) return;
  esp_now_set_self_role(ESP_NOW_ROLE_CONTROLLER);
  esp_now_register_send_cb(OnDataSent);
  esp_now_add_peer(broadcastAddress, ESP_NOW_ROLE_SLAVE, 1, NULL, 0);
#elif defined(ESP32)
  if (esp_now_init() != ESP_OK) return;
  esp_now_register_send_cb(OnDataSent);
  
  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, broadcastAddress, 6);
  peerInfo.channel = 0;
  peerInfo.encrypt = false;
  esp_now_add_peer(&peerInfo);
#endif

  // Set the node_id once in setup
  myData.node_id = 1; 
}

void loop() {
  // Update the transmission data
  myData.transmission = random(0, 10000) / 100.0;
  
  Serial.print("Sending Node ID: ");
  Serial.print(myData.node_id);
  Serial.print(" | Transmission: ");
  Serial.println(myData.transmission);

  esp_now_send(broadcastAddress, (uint8_t *) &myData, sizeof(myData));

  delay(2000);
}