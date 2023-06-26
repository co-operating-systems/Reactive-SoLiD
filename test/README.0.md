## SoLiD implementation in akka

Initial [SoLiD](https://github.com/solid/solid-spec) implementation with [akka](http://akka.io/).
This is a re-write of [rww-play](https://github.com/read-write-web/rww-play).

Reactive Solid is developed as part of the [Solid-Control](https://nlnet.nl/project/SolidControl/) project, which was funded by the the [Next Generation Internet (NGI0)](https://nlnet.nl/NGI0/) in their [Privacy and Trust Enhancing Technologies (PET)](https://nlnet.nl/PET/) call.

### File system structure

Reactive-Solid uses the file system to store resources. 
In order for it to work efficiently with very large number of files, it avoids loading a list of all the files in a directory. Instead resources are identified as the initial part up to and excluding the `.`. The server checks
the existence of that resource by looking for a symbolic link of that name, which links then to the default
representation.

For example, if the server is asked for the resource `http://localhost:8080/README` it will look for a symbolic link
`README` in the root directory. That points to `README.0.md` indicating that this is the first version of that file, and
that it is a markdown file. (the extensions are those given by the Akka mime type library for the moment).

Access control files are symbolic links that end with `.acl`. A directory acl is just `.acl`.

```bash
ls  -al ldes/openCF/
total 40
drwxr-xr-x@ 12 cosy  admin   384 Mar 15 15:06 .
drwxr-xr-x@  8 cosy  admin   256 Jun  3 19:37 ..
lrwxr-xr-x@  1 cosy  admin    10 Mar 14 11:15 .acl -> .acl.1.ttl
-rw-r--r--@  1 cosy  admin   350 Mar 14 11:14 .acl.1.ttl
lrwxr-xr-x@  1 cosy  admin    16 Mar 14 11:55 2021-09-05 -> 2021-09-05.1.ttl
-rw-r--r--@  1 cosy  admin  2211 Mar 15 14:54 2021-09-05.1.ttl
lrwxr-xr-x@  1 cosy  admin    16 Mar 14 11:55 2021-09-06 -> 2021-09-06.1.ttl
-rw-r--r--@  1 cosy  admin  2487 Mar 15 15:06 2021-09-06.1.ttl
lrwxr-xr-x@  1 cosy  admin    16 Mar 14 11:55 2021-09-07 -> 2021-09-07.1.ttl
-rw-r--r--@  1 cosy  admin  2177 Mar 15 14:55 2021-09-07.1.ttl
lrwxr-xr-x@  1 cosy  admin    12 Mar 15 14:53 stream -> stream.1.ttl
-rw-r--r--@  1 cosy  admin   244 Mar 15 14:53 stream.1.ttl
```

### Test Dir layout

The directory structure is as follows: 

```bash
tree -d
.
├── ldes
│   ├── bigClosedCF
│   │   └── 2021
│   │       └── 09
│   ├── closedCF
│   ├── defaultCF
│   └── openCF
├── people
├── protected
```

The ldes directory contains a number of LDES streams.
