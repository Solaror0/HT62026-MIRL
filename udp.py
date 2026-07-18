import socket

# 0.0.0.0 tells the socket to listen on all available network interfaces
UDP_IP = "0.0.0.0" 
UDP_PORT = 4210

# Create a UDP socket
# AF_INET means IPv4, SOCK_DGRAM means UDP
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# Bind the socket to the port
sock.bind((UDP_IP, UDP_PORT))

print(f"Listening for incoming UDP packets on port {UDP_PORT}...")

try:
    while True:
        # 1024 is the buffer size in bytes
        data, addr = sock.recvfrom(1024) 
        
        # addr contains the IP and port of the sender (the ESP)
        sender_ip = addr[0]
        
        # Decode the byte data back into a string
        message = data.decode('utf-8')
        
        print(f"Received from ESP at {sender_ip}: {message}")
        
except KeyboardInterrupt:
    print("\nListener stopped.")
    sock.close()