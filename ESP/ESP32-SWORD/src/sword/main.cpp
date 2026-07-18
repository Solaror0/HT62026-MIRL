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
  Wire.begin(25, 26); 
  if (!mpu.begin(0x68)) { 
    Serial.println("Failed to find MPU6050 at 0x68");
    while (1);
} else {

  Serial.println("Yo we good on the MPU side");
}
  
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();

  // ESP-NOW Initialization
  #if defined(ESP32)
    esp_now_init();
    esp_now_peer_info_t peerInfo = {};
    memcpy(peerInfo.peer_addr, broadcastAddress, 6);
    esp_now_add_peer(&peerInfo);
  #endif

  myData.node_id = 1;
  myData.right_click = 0;
}

void loop() {
  sensors_event_t a, g, temp;
  mpu.getEvent(&a, &g, &temp);

  // 1. Detection Logic
  float accelMag = sqrt(pow(a.acceleration.x, 2) + pow(a.acceleration.y, 2));
  
  // Reset states
  myData.picked_up = (accelMag > 2.0);
  myData.left_click = (abs(g.gyro.z) > 3.0) ? 1 : 0; //change this to directional when you know how you're gonna place it/ use it
  
  //(abs(g.gyro.x) > 3.0 || abs(g.gyro.y) > 3.0) ? 1 : 0; 


  Serial.print("Gyro X ");Serial.print(g.gyro.x); Serial.print(" Gyro Z ");Serial.print(g.gyro.z);  Serial.print(" Gyro Y ");Serial.println(g.gyro.y);
  Serial.print("Left Click: ");
  Serial.print(accelMag);
  Serial.println(myData.left_click);
 
  Serial.println();

  // 2. Send Data
  esp_now_send(broadcastAddress, (uint8_t *) &myData, sizeof(myData));

  delay(100); 
}