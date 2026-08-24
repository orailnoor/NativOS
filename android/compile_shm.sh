cp /data/local/tmp/libandroid-shmem.c /data/user/0/com.nativOS/files/rootfs/root/
chroot /data/user/0/com.nativOS/files/rootfs /bin/bash -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; gcc -shared -fPIC -o /root/libandroid-shmem.so /root/libandroid-shmem.c'
cp /data/user/0/com.nativOS/files/rootfs/root/libandroid-shmem.so /data/local/tmp/
