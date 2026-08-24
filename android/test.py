import socket
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect('/tmp/.X11-unix/X0')
print('Success')
