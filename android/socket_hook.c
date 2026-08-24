#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <unistd.h>
#include <stddef.h>

int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    static int (*real_connect)(int, const struct sockaddr *, socklen_t) = NULL;
    if (!real_connect) {
        real_connect = dlsym(RTLD_NEXT, "connect");
    }

    if (addr && addr->sa_family == AF_UNIX) {
        const struct sockaddr_un *un = (const struct sockaddr_un *)addr;

        if (un->sun_path[0] != 0 &&
            strstr(un->sun_path, "/.X11-unix/X") != NULL) {

            const char *tmpdir = getenv("TMPDIR");
            if (tmpdir && strlen(tmpdir) > 0) {
                const char *x_part = strstr(un->sun_path, "/.X11-unix/X");

                struct sockaddr_un new_addr;
                memset(&new_addr, 0, sizeof(new_addr));
                new_addr.sun_family = AF_UNIX;
                new_addr.sun_path[0] = 0; 

                char abstract_path[108];
                snprintf(abstract_path, sizeof(abstract_path),
                         "%s%s", tmpdir, x_part);
                memcpy(new_addr.sun_path + 1, abstract_path, strlen(abstract_path));

                socklen_t new_len = offsetof(struct sockaddr_un, sun_path)
                                    + 1 + strlen(abstract_path);

                fprintf(stderr, "NativOS socket_hook: Redirecting %s -> @%s\n",
                        un->sun_path, abstract_path);

                return real_connect(sockfd, (struct sockaddr *)&new_addr, new_len);
            }
        }
    }

    return real_connect(sockfd, addr, addrlen);
}
