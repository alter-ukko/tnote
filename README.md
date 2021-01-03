## tnote

tnote is a command-line note-taking app.

## building

```
cd tnote
./build
ln -s target/tnote/tn ~/home/bin/tn
ln -s target/tnote/tnq ~/home/bin/tnq
```

## running

create a file `~/.tnode` with content like this:

```
storage=~/files/tnote
editor=subl
viewer=eog
browser=firefox
```

When you do `tn edit` it will use whatever `$EDITOR` is set to, or `/usr/bin/nano` if it's not set.

Run `tn` or `tnq --help` to see a list of options.