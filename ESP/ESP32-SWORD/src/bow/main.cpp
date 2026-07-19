#include <Adafruit_MPU6050.h>
#include <Adafruit_Sensor.h>
#include <Wire.h>

#if defined(ESP8266)
  #include <ESP8266WiFi.h>
  #include <espnow.h>
#elif defined(ESP32)
  #include <WiFi.h>
  #include <esp_now.h>
#endif

Adafruit_MPU6050 mpu;
uint8_t broadcastAddress[] = {0xEC, 0xFA, 0xBC, 0x4B, 0x3F, 0x93};

typedef struct struct_message {
  int node_id;     // E.g., 1, 2, 3... to identify which ESP is sending
  int picked_up;
  int left_click;
  int right_click;
} struct_message;

struct_message myData;

void setup() {
  Serial.begin(115200);

  // Initialize Wire with custom pins 25 (SDA) and 26 (SCL)
//   Wire.begin(25, 26); 
//   if (!mpu.begin(0x68)) { 
//     Serial.println("Failed to find MPU6050 at 0x68");
//     while (1);
// } else {

//   Serial.println("Yo we good on the MPU side");
// }
  
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  //circuit closing setup

  pinMode(14,INPUT_PULLUP);

  // ESP-NOW Initialization
  #if defined(ESP32)
    esp_now_init();
    esp_now_peer_info_t peerInfo = {};
    memcpy(peerInfo.peer_addr, broadcastAddress, 6);
    esp_now_add_peer(&peerInfo);
  #endif

  myData.node_id = 3;
  
}

void loop() {
  // sensors_event_t a, g, temp;

  // mpu.getEvent(&a, &g, &temp);
  
  // float accelMag = sqrt(pow(a.acceleration.x, 2) + pow(a.acceleration.y, 2));
  //   myData.picked_up = (accelMag > 4.0);
// Neatly formatted serial output
  // Serial.print("AccX: "); Serial.print(a.acceleration.x);
  // Serial.print("\tAccY: "); Serial.print(accYNow);
  // Serial.print("\tAccYOld: "); Serial.print(accYPrev);
  // Serial.print("\tAccZ: "); Serial.print(a.acceleration.z);
  
  // Serial.print("\t| GyroX: "); Serial.print(g.gyro.x);
  // Serial.print("\tGyroY: "); Serial.print(g.gyro.y);
  // Serial.print("\tGyroZ: "); Serial.println(g.gyro.z);


  int closed_state = digitalRead(14);

  myData.right_click = (closed_state == LOW) ? 1 : 0;

  Serial.print("R click: "); Serial.print(myData.right_click);

  // Serial.print(" Pickup "); Serial.println(myData.picked_up); Serial.println();

  esp_now_send(broadcastAddress, (uint8_t *) &myData, sizeof(myData));

  delay(400); 
}