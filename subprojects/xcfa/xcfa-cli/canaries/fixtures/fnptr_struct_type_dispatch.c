// A call through a function pointer only dispatches to functions it can legally reach. `lo.done`
// is never written, and the only address-taken function of the right arity is `probe`, whose
// parameter is a pointer to a different struct: calling it through `done` is undefined, so the
// error inside `probe` is unreachable. Matching candidates by arity alone reported it.
extern void abort(void);
void reach_error() { abort(); }

struct A { int x; };
struct B { long y; long z; };
int locked = 0;
void probe(struct B *b) {
  if (locked) reach_error();
}
struct ops {
  void (*done)(struct A *);
  void (*probe)(struct B *);
};

int main() {
  struct A a;
  struct ops lo;
  lo.probe = probe;
  locked = 1;
  (*lo.done)(&a);
  locked = 0;
  return 0;
}
