# -Pprofiling=true: readable profiles (simpleperf). Names are kept and classes aren't merged, so each sample is
# charged to the code that really ran (merged lambdas otherwise show under another class's name). Slower than
# a real release: compare such builds with each other, not with a shipped one.
-dontobfuscate
-dontoptimize
