## tnote

tnote is a command-line note-taking app (and very minimal to-do app).

## building

```
cd tnote
gradle build
```

This will produce a `tnote-{version}.zip` file in `build/distributions` Unzip this somewhere and symlink the scripts in `tnote-{version}/bin` to `~/bin` (or wherever on your path you like to run things from). 

## running

There are three scripts that come with `tnote`:
* `tn` - take a note
* `tnq` - query your notes
* `td` - manage to-do tasks

The first time you run `tn` it will tell you to create a file `~/.tnode` with content like this:

```properties
storage={full path to storage location}
editor={text editor command}
viewer={image viewer command}
browser={web browser command}
stylesheet={optional full path to stylesheet for html rendering}
```

For example, under Linux, using Sublime Text as an editor, Eye of Gnome as an image/pdf viewer, and Firefox as a browser (and keeping the default stylesheet bundled with `tnote`), you might have: 

```properties
storage=~/files/tnote
editor=subl
viewer=eog
browser=firefox
```

When you do `tn edit` it will use whatever `$EDITOR` is set to, or `/usr/bin/nano` if it's not set.

Run `tn`, `td` or `tnq --help` to see a list of options for each of those respective scripts.
