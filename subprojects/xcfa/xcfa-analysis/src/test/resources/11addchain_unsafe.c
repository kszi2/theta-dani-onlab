void reach_error(){}
int main() {
    int a = 1;
    int b = a + 2;
    int c = b + 2;
    if(c == 5) reach_error();
}
