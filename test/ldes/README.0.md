# Linked Data Event Stream Tests

This directory contains a number of Linked Data Event Stream [LDES](https://semiceu.github.io/LinkedDataEventStreams/)
examples which can be used to test a crawler such as the one implemented
in [SolidCtrl App](https://github.com/bblfish/SolidCtrlApp).

3 directories contain a small set of data with different access control rules
- [openCF](./openCF/) - all the files are world readable
- [closedCF](./closedCF/) - each file has its own access control rule
- [defaultCF](./defaultCF/) - there is a default access control rule for the whole directory

then we have a bigger set of data organised hierarchically in `yyyy/mm/` directories with
one [acl](bigClosedCF/.acl.1.ttl) at the root that covers all the subdirectories.

- [bigClosedCF](./bigClosedCF/)
  - [2021/09](./bigClosedCF/2021/09/)
  
Todo: it would be useful to subdivide each day into directory, and 
perhaps even make the files smaller.  
