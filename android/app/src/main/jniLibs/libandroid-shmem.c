#include <sys/syscall.h>
#include <unistd.h>
#include <sys/ipc.h>
#include <sys/shm.h>
#include <sys/types.h>
#include <errno.h>

#ifndef __NR_shmget
#define __NR_shmget 194
#define __NR_shmat 196
#define __NR_shmctl 195
#define __NR_shmdt 197
#endif

int shmget(key_t key, size_t size, int shmflg) {
    return syscall(__NR_shmget, key, size, shmflg);
}

void *shmat(int shmid, const void *shmaddr, int shmflg) {
    return (void *)syscall(__NR_shmat, shmid, shmaddr, shmflg);
}

int shmctl(int shmid, int cmd, struct shmid_ds *buf) {
    return syscall(__NR_shmctl, shmid, cmd, buf);
}

int shmdt(const void *shmaddr) {
    return syscall(__NR_shmdt, shmaddr);
}
