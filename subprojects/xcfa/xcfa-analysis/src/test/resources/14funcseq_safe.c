void reach_error(){}

int add_two(int x) {
    return x + 2;
}

int main() {
    int a = 3;
    int b = add_two(a);
    if(b == 99) reach_error();
}
