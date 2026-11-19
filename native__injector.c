/*
 * Standalone AArch64 root tool for controlling an injected library in a
 * running process via ptrace.
 *
 * Subcommands:
 *   injector inject <pid> <lib_path>
 *       Loads <lib_path> into <pid> via a remote dlopen() call. If the
 *       library has a __attribute__((constructor)) entry point, it already
 *       ran by the time dlopen() returns, since the dynamic linker runs
 *       constructors before dlopen hands control back to the caller.
 *
 *   injector call <pid> <lib_path> <symbol> [arg]
 *       Calls an already-exported C function named <symbol> inside the
 *       module at <lib_path> that is already loaded in <pid> (normally
 *       after a prior `inject`). <lib_path> is read from disk only to look
 *       up <symbol>'s offset in its .dynsym section; the library is never
 *       loaded or executed locally, only parsed as a file, specifically so
 *       that this process never runs the library's own constructor (which
 *       expects to be inside a Unity/IL2CPP process, not a bare root tool).
 *       <symbol> must take 0 or 1 integer-sized argument and return an
 *       integer-sized value (void is fine for either); this does not work
 *       for functions with a different signature. [arg] is parsed with
 *       strtoull base 0 (accepts decimal or 0x-prefixed hex); omit it to
 *       call with a 0 argument.
 *
 * Must run as root (uid 0), e.g. via `su -c "./injector inject <pid> <lib>"`.
 *
 * Technique shared by both subcommands: find a module's load base in both
 * our own process and the target's (from /proc/<pid>/maps), and combine
 * that with an offset measured from the file (the file's own .dynsym for
 * `call`; dlsym() against the live system module for `inject`'s dlopen/
 * dlerror) to get the function's address in the target. This assumes the
 * exact same build of the module is mapped in both processes, which holds
 * when both are the same device/OS image, but is confirmed at runtime by
 * checking the resulting address actually falls in executable memory
 * before ever jumping to it.
 */
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

/* AArch64 general-purpose register file, as exposed by PTRACE_GETREGSET /
 * PTRACE_SETREGSET with NT_PRSTATUS. Layout matches the kernel's
 * struct user_pt_regs for arm64: 31 general registers, then sp, pc, pstate. */
struct aarch64_regs {
    unsigned long long regs[31];
    unsigned long long sp;
    unsigned long long pc;
    unsigned long long pstate;
};

static int get_regs(pid_t pid, struct aarch64_regs *out) {
    struct iovec iov = {out, sizeof(*out)};
    return ptrace(PTRACE_GETREGSET, pid, (void *)NT_PRSTATUS, &iov);
}

static int set_regs(pid_t pid, struct aarch64_regs *in) {
    struct iovec iov = {in, sizeof(*in)};
    return ptrace(PTRACE_SETREGSET, pid, (void *)NT_PRSTATUS, &iov);
}

/* Writes `len` bytes to the target's memory at `addr`, word (8 byte) at a
 * time via PTRACE_POKEDATA. `addr` need not be 8-byte aligned; any partial
 * leading/trailing word is read-modify-written so bytes outside [addr,
 * addr+len) at the edges are preserved. */
static int write_remote(pid_t pid, unsigned long long addr, const void *buf, size_t len) {
    size_t i = 0;
    while (i < len) {
        unsigned long long word_addr = addr + i;
        unsigned long long aligned = word_addr & ~7ULL;
        size_t offset_in_word = word_addr - aligned;
        errno = 0;
        unsigned long long word = ptrace(PTRACE_PEEKDATA, pid, (void *)aligned, NULL);
        if (word == (unsigned long long)-1 && errno != 0) return -1;
        size_t chunk = 8 - offset_in_word;
        if (chunk > len - i) chunk = len - i;
        memcpy((char *)&word + offset_in_word, (const char *)buf + i, chunk);
        if (ptrace(PTRACE_POKEDATA, pid, (void *)aligned, (void *)word) != 0) return -1;
        i += chunk;
    }
    return 0;
}

/* Finds the lowest mapped start address of any region whose path in
 * /proc/<pid>/maps ends with `libname`. Returns 0 if not found. */
static unsigned long long find_module_base(pid_t pid, const char *libname) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[1024];
    unsigned long long base = 0;
    size_t namelen = strlen(libname);
    while (fgets(line, sizeof(line), f)) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = 0;
        size_t linelen = strlen(line);
        if (linelen < namelen) continue;
        if (strcmp(line + linelen - namelen, libname) != 0) continue;
        unsigned long long start = strtoull(line, NULL, 16);
        if (base == 0 || start < base) base = start;
    }
    fclose(f);
    return base;
}

/* Prints every /proc/<pid>/maps line whose path ends with `libname`, with a
 * tag so local and remote output can be told apart. Pure diagnostics: does
 * not affect the injection logic. */
static void dump_matching_maps(pid_t pid, const char *libname, const char *tag) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) {
        printf("[%s] could not open %s\n", tag, path);
        return;
    }
    char line[1024];
    size_t namelen = strlen(libname);
    int count = 0;
    while (fgets(line, sizeof(line), f)) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = 0;
        size_t linelen = strlen(line);
        if (linelen < namelen) continue;
        if (strcmp(line + linelen - namelen, libname) != 0) continue;
        printf("[%s] %s\n", tag, line);
        count++;
    }
    if (count == 0) printf("[%s] no line in %s ends with %s\n", tag, path, libname);
    fclose(f);
}

/* Returns 1 if `addr` falls inside a mapped region of pid's maps that has
 * the executable bit set, 0 if it falls in a mapped-but-not-executable
 * region, and -1 if it is not mapped at all (or maps could not be read). */
static int is_executable_at(pid_t pid, unsigned long long addr) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char line[1024];
    int result = -1;
    while (fgets(line, sizeof(line), f)) {
        unsigned long long start, end;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &start, &end, perms) != 3) continue;
        if (addr >= start && addr < end) {
            result = (strchr(perms, 'x') != NULL) ? 1 : 0;
            break;
        }
    }
    fclose(f);
    return result;
}

/* Calls `func_addr(arg0)` (or no args, if nargs==0) inside the already-
 * attached, already-stopped `pid`, using `orig_regs` (the registers
 * captured right after attaching) as the base to restore sp/other state
 * from for this one call. lr is set to 0 so the call's `ret` lands at
 * pc==0, used as a catchable trap distinguishing "the call returned" from
 * "something else happened" (a crash, a signal). On success, *out_result
 * gets x0 and this returns 0; otherwise returns -1 and the target's
 * registers may be left mid-call, so the caller must still restore
 * orig_regs itself afterwards regardless of this call's outcome. */
static int remote_call(pid_t pid, const struct aarch64_regs *orig_regs,
                        unsigned long long func_addr, unsigned long long arg0, int nargs,
                        unsigned long long *out_result) {
    struct aarch64_regs call_regs = *orig_regs;
    unsigned long long scratch = (orig_regs->sp - 0x1000) & ~0xFULL;
    call_regs.regs[0] = nargs > 0 ? arg0 : 0;
    call_regs.regs[30] = 0;
    call_regs.pc = func_addr;
    call_regs.sp = scratch;

    if (set_regs(pid, &call_regs) != 0) return -1;
    if (ptrace(PTRACE_CONT, pid, NULL, NULL) != 0) return -1;
    int status;
    if (waitpid(pid, &status, 0) < 0 || !WIFSTOPPED(status)) return -1;
    struct aarch64_regs after;
    if (get_regs(pid, &after) != 0 || after.pc != 0) return -1;
    *out_result = after.regs[0];
    return 0;
}

/* Reads a NUL-terminated string from the target's memory at `addr`, up to
 * bufsize-1 bytes. Returns 0 on success (buf is always NUL-terminated),
 * -1 if a PEEKDATA call failed (e.g. addr unmapped). */
static int read_remote_string(pid_t pid, unsigned long long addr, char *buf, size_t bufsize) {
    size_t i = 0;
    while (i + 8 < bufsize) {
        errno = 0;
        unsigned long long word = ptrace(PTRACE_PEEKDATA, pid, (void *)(addr + i), NULL);
        if (word == (unsigned long long)-1 && errno != 0) {
            buf[i] = 0;
            return -1;
        }
        memcpy(buf + i, &word, 8);
        for (int j = 0; j < 8; j++) {
            if (buf[i + j] == '\0') return 0;
        }
        i += 8;
    }
    buf[bufsize - 1] = 0;
    return 0;
}

/* Looks up `symbol` in the ELF64 .dynsym section of the file at `path`,
 * reading only from disk: never dlopen()s or otherwise executes the file.
 * This matters specifically for libraries with a constructor that expects
 * to already be running inside a particular host process (here, a Unity/
 * IL2CPP game), which would misbehave or crash if loaded into this bare
 * tool instead. On success, *out_value gets the symbol's st_value, which
 * for a position-independent (ET_DYN) library is its offset from the
 * module's load base, and this returns 0; returns -1 if the file, its
 * section headers, .dynsym/.dynstr, or the symbol itself cannot be found. */
static int find_symbol_offset(const char *path, const char *symbol, unsigned long long *out_value) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;

    Elf64_Ehdr eh;
    if (fread(&eh, sizeof(eh), 1, f) != 1 ||
        memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0 ||
        eh.e_ident[EI_CLASS] != ELFCLASS64) {
        fclose(f);
        return -1;
    }

    Elf64_Shdr *shdrs = malloc((size_t)eh.e_shnum * sizeof(Elf64_Shdr));
    if (!shdrs || fseek(f, (long)eh.e_shoff, SEEK_SET) != 0 ||
        fread(shdrs, sizeof(Elf64_Shdr), eh.e_shnum, f) != eh.e_shnum) {
        free(shdrs);
        fclose(f);
        return -1;
    }

    Elf64_Shdr *shstr = &shdrs[eh.e_shstrndx];
    char *shstrtab = malloc(shstr->sh_size);
    if (!shstrtab || fseek(f, (long)shstr->sh_offset, SEEK_SET) != 0 ||
        fread(shstrtab, 1, shstr->sh_size, f) != shstr->sh_size) {
        free(shstrtab);
        free(shdrs);
        fclose(f);
        return -1;
    }

    Elf64_Shdr *dynsym = NULL, *dynstr = NULL;
    for (int i = 0; i < eh.e_shnum; i++) {
        const char *name = shstrtab + shdrs[i].sh_name;
        if (strcmp(name, ".dynsym") == 0) dynsym = &shdrs[i];
        else if (strcmp(name, ".dynstr") == 0) dynstr = &shdrs[i];
    }

    int result = -1;
    if (dynsym && dynstr && dynsym->sh_entsize == sizeof(Elf64_Sym)) {
        char *strtab = malloc(dynstr->sh_size);
        size_t count = dynsym->sh_size / sizeof(Elf64_Sym);
        Elf64_Sym *syms = malloc(dynsym->sh_size);
        if (strtab && syms &&
            fseek(f, (long)dynstr->sh_offset, SEEK_SET) == 0 &&
            fread(strtab, 1, dynstr->sh_size, f) == dynstr->sh_size &&
            fseek(f, (long)dynsym->sh_offset, SEEK_SET) == 0 &&
            fread(syms, sizeof(Elf64_Sym), count, f) == count) {
            for (size_t i = 0; i < count; i++) {
                if (syms[i].st_name == 0 || syms[i].st_name >= dynstr->sh_size) continue;
                if (strcmp(strtab + syms[i].st_name, symbol) == 0) {
                    *out_value = syms[i].st_value;
                    result = 0;
                    break;
                }
            }
        }
        free(strtab);
        free(syms);
    }

    free(shstrtab);
    free(shdrs);
    fclose(f);
    return result;
}

/* Extracts the final path component, e.g. ".../aero/liblemmingrmt.so" ->
 * "liblemmingrmt.so", used to match the module in /proc/<pid>/maps the
 * same way regardless of which directory it was staged in. */
static const char *basename_of(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static int do_inject(pid_t target, const char *lib_path) {
    size_t lib_path_len = strlen(lib_path) + 1; /* include NUL */

    /* dlopen's documented, stable home in the Android NDK/bionic ABI is
     * libdl.so, which is what apps are supposed to link against; libc.so is
     * tried only as a fallback. */
    const char *module_name = "libdl.so";
    void *mod_handle = dlopen(module_name, RTLD_NOW | RTLD_NOLOAD);
    if (!mod_handle) mod_handle = dlopen(module_name, RTLD_NOW);
    void *local_dlopen = mod_handle ? dlsym(mod_handle, "dlopen") : NULL;
    if (!local_dlopen) {
        module_name = "libc.so";
        mod_handle = dlopen(module_name, RTLD_NOW | RTLD_NOLOAD);
        if (!mod_handle) mod_handle = dlopen(module_name, RTLD_NOW);
        local_dlopen = mod_handle ? dlsym(mod_handle, "dlopen") : NULL;
    }
    if (!local_dlopen) {
        fprintf(stderr, "could not resolve dlopen from libdl.so or libc.so locally: %s\n", dlerror());
        return 1;
    }
    printf("resolved dlopen from %s at local address %p\n", module_name, local_dlopen);

    dump_matching_maps(getpid(), module_name, "local");
    dump_matching_maps(target, module_name, "remote");

    unsigned long long local_base = find_module_base(getpid(), module_name);
    if (local_base == 0) {
        fprintf(stderr, "could not find %s base in our own /proc/self/maps\n", module_name);
        return 1;
    }
    unsigned long long dlopen_offset = (unsigned long long)local_dlopen - local_base;

    unsigned long long remote_base = find_module_base(target, module_name);
    if (remote_base == 0) {
        fprintf(stderr, "could not find %s base for pid %d (is it running? permission?)\n", module_name, target);
        return 1;
    }
    unsigned long long remote_dlopen = remote_base + dlopen_offset;
    printf("local %s base=0x%llx remote %s base=0x%llx dlopen offset=0x%llx remote dlopen=0x%llx\n",
           module_name, local_base, module_name, remote_base, dlopen_offset, remote_dlopen);

    int exec_check = is_executable_at(target, remote_dlopen);
    if (exec_check != 1) {
        fprintf(stderr,
                "remote dlopen address is %s in the target (exec_check=%d); refusing to jump there.\n",
                exec_check == 0 ? "mapped but NOT executable" : "not mapped at all", exec_check);
        return 1;
    }

    void *local_dlerror = dlsym(mod_handle, "dlerror");
    unsigned long long remote_dlerror = 0;
    if (local_dlerror) {
        unsigned long long dlerror_offset = (unsigned long long)local_dlerror - local_base;
        remote_dlerror = remote_base + dlerror_offset;
        if (is_executable_at(target, remote_dlerror) != 1) remote_dlerror = 0;
    }

    if (ptrace(PTRACE_ATTACH, target, NULL, NULL) != 0) {
        fprintf(stderr, "PTRACE_ATTACH(%d) failed: %s\n", target, strerror(errno));
        return 1;
    }
    int status;
    if (waitpid(target, &status, 0) < 0 || !WIFSTOPPED(status)) {
        fprintf(stderr, "waitpid after attach did not report stopped (status=0x%x)\n", status);
        ptrace(PTRACE_DETACH, target, NULL, NULL);
        return 1;
    }

    struct aarch64_regs orig_regs;
    if (get_regs(target, &orig_regs) != 0) {
        fprintf(stderr, "PTRACE_GETREGSET failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target, NULL, NULL);
        return 1;
    }

    unsigned long long str_addr = ((orig_regs.sp - 0x1000) & ~0xFULL) - 0x200;

    int ok = 0;
    unsigned long long remote_handle = 0;
    if (write_remote(target, str_addr, lib_path, lib_path_len) != 0) {
        fprintf(stderr, "failed writing library path into target memory\n");
    } else {
        struct aarch64_regs call_regs = orig_regs;
        call_regs.regs[0] = str_addr;
        call_regs.regs[1] = 2; /* RTLD_NOW */
        call_regs.regs[30] = 0;
        call_regs.pc = remote_dlopen;
        call_regs.sp = (orig_regs.sp - 0x1000) & ~0xFULL;
        if (set_regs(target, &call_regs) != 0) {
            fprintf(stderr, "PTRACE_SETREGSET (dlopen call) failed: %s\n", strerror(errno));
        } else if (ptrace(PTRACE_CONT, target, NULL, NULL) != 0) {
            fprintf(stderr, "PTRACE_CONT failed: %s\n", strerror(errno));
        } else if (waitpid(target, &status, 0) < 0 || !WIFSTOPPED(status)) {
            fprintf(stderr, "target did not stop as expected after dlopen call (status=0x%x)\n", status);
        } else {
            struct aarch64_regs after;
            if (get_regs(target, &after) == 0 && after.pc == 0) {
                remote_handle = after.regs[0];
                ok = 1;
            } else {
                fprintf(stderr, "stopped at unexpected pc=0x%llx; dlopen call result unknown\n", after.pc);
            }
        }
    }

    if (ok && remote_handle == 0 && remote_dlerror != 0) {
        unsigned long long err_ptr = 0;
        if (remote_call(target, &orig_regs, remote_dlerror, 0, 0, &err_ptr) == 0 && err_ptr != 0) {
            char msg[256];
            if (read_remote_string(target, err_ptr, msg, sizeof(msg)) == 0) {
                printf("dlerror() in target says: %s\n", msg);
            }
        }
    }

    set_regs(target, &orig_regs);
    ptrace(PTRACE_DETACH, target, NULL, NULL);

    if (!ok) {
        fprintf(stderr, "injection failed\n");
        return 1;
    }
    if (remote_handle == 0) {
        fprintf(stderr, "remote dlopen returned NULL: see the dlerror() message above\n");
        return 1;
    }
    printf("remote dlopen succeeded, handle=0x%llx\n", remote_handle);
    return 0;
}

static int do_call(pid_t target, const char *lib_path, const char *symbol, int has_arg, unsigned long long arg) {
    unsigned long long offset = 0;
    if (find_symbol_offset(lib_path, symbol, &offset) != 0) {
        fprintf(stderr, "symbol '%s' not found in %s's .dynsym\n", symbol, lib_path);
        return 1;
    }

    const char *module_name = basename_of(lib_path);
    unsigned long long remote_base = find_module_base(target, module_name);
    if (remote_base == 0) {
        fprintf(stderr, "could not find %s base for pid %d; is it injected and still running?\n", module_name, target);
        return 1;
    }
    unsigned long long remote_addr = remote_base + offset;
    printf("%s+0x%llx -> remote address 0x%llx\n", module_name, offset, remote_addr);

    if (is_executable_at(target, remote_addr) != 1) {
        fprintf(stderr, "remote address 0x%llx is not executable in the target; refusing to call it\n", remote_addr);
        return 1;
    }

    if (ptrace(PTRACE_ATTACH, target, NULL, NULL) != 0) {
        fprintf(stderr, "PTRACE_ATTACH(%d) failed: %s\n", target, strerror(errno));
        return 1;
    }
    int status;
    if (waitpid(target, &status, 0) < 0 || !WIFSTOPPED(status)) {
        fprintf(stderr, "waitpid after attach did not report stopped (status=0x%x)\n", status);
        ptrace(PTRACE_DETACH, target, NULL, NULL);
        return 1;
    }

    struct aarch64_regs orig_regs;
    if (get_regs(target, &orig_regs) != 0) {
        fprintf(stderr, "PTRACE_GETREGSET failed: %s\n", strerror(errno));
        ptrace(PTRACE_DETACH, target, NULL, NULL);
        return 1;
    }

    unsigned long long result = 0;
    int ok = remote_call(target, &orig_regs, remote_addr, arg, has_arg, &result) == 0;

    set_regs(target, &orig_regs);
    ptrace(PTRACE_DETACH, target, NULL, NULL);

    if (!ok) {
        fprintf(stderr, "call did not land cleanly (crash, signal, or ptrace error)\n");
        return 1;
    }
    printf("result=0x%llx (%llu)\n", result, result);
    return 0;
}

int main(int argc, char **argv) {
    if (getuid() != 0) {
        fprintf(stderr, "must run as root (uid 0); got uid %d\n", getuid());
        return 2;
    }
    if (argc >= 4 && strcmp(argv[1], "inject") == 0) {
        return do_inject((pid_t)atoll(argv[2]), argv[3]);
    }
    if (argc >= 5 && strcmp(argv[1], "call") == 0) {
        pid_t pid = (pid_t)atoll(argv[2]);
        const char *lib_path = argv[3];
        const char *symbol = argv[4];
        int has_arg = argc >= 6;
        unsigned long long arg = has_arg ? strtoull(argv[5], NULL, 0) : 0;
        return do_call(pid, lib_path, symbol, has_arg, arg);
    }
    fprintf(stderr,
            "usage:\n"
            "  %s inject <pid> <lib_path>\n"
            "  %s call <pid> <lib_path> <symbol> [arg]\n",
            argv[0], argv[0]);
    return 2;
}
