#define _GNU_SOURCE
#include <stdio.h>
#include <dlfcn.h>
#include <string.h>
#include <xcb/xcb.h>

struct my_xcb_extension_t {
    const char *name;
    int global_id;
};

const struct xcb_query_extension_reply_t *
xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    static const struct xcb_query_extension_reply_t * (*real_xcb_get_extension_data)(xcb_connection_t *, xcb_extension_t *) = NULL;

    if (!real_xcb_get_extension_data) {
        real_xcb_get_extension_data = dlsym(RTLD_NEXT, "xcb_get_extension_data");
    }

    struct my_xcb_extension_t *my_ext = (struct my_xcb_extension_t *)ext;
    if (my_ext && my_ext->name && strcmp(my_ext->name, "DRI3") == 0) {
        fprintf(stderr, "libnodri3: Blocking DRI3 extension query\n");
        return NULL;
    }

    return real_xcb_get_extension_data(c, ext);
}
