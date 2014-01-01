# MireDroid
MireDroid gives Android IPv6 connectivity using Teredo tunneling.
It uses the `miredo` client.
## Installing
Tested with `mksh` and Android API Level 17 (4.2). In theory, API Level 9 (2.3) and up should work.
Below that, bionic's pthreads is deficient.
The prebuilt APK is packaged with only the API Level 9 ARM binaries.
## Building
1. Either build `miredo.zip` from yingted/miredo@android or copy it from the initial commit.
2. Add any alternative resources for different device configurations.
3. Build from Eclipse using ADT.
