## Mixed Text Styles in Figma

> Many text properties can be set on individual characters. For that reason, you will find that many of text properties can return the special value figma.mixed if there are characters in the text with different values for that property. Always consider that a text properties could have mixed values.

- https://www.figma.com/plugin-docs/api/TextNode/
- https://www.figma.com/plugin-docs/api/properties/figma-mixed/

Getting font size for each character in a mixed text would work something like this:

```
let len = textNode.characters.length
let fontSizeRanges = {}
for (let i = 0; i < len; i++) {
  const fontSize = textNode.getRangeFontSize(i, i+1)
  if (fontSizeRanges[fontSize]) {
    fontSizeRanges[fontSize] = Object.assign(fontSizeRanges[fontSize], {end: i+1})
  } else {
    fontSizeRanges[fontSize] = {start: i}
  }
}
console.log(fontSizeRanges)
```

`fontSizeRanges` would now contain the different font sizes + which ranges they
are used in. This will then have to be converted into something we can use
in the core-api for Instant Website. Example output of `fontSizeRanges`:

```
{
  "14": {
    "start": 29,
    "end": 34
  },
  "18": {
    "start": 19,
    "end": 29
  },
  "24": {
    "start": 14,
    "end": 19
  },
  "36": {
    "start": 10,
    "end": 14
  },
  "48": {
    "start": 5,
    "end": 10
  },
  "64": {
    "start": 0,
    "end": 5
  }
}
```

Where do we want the split of text to happen? The output we need for HTML and CSS
purposes are <span> for each splitted element. Question is if we wanna do that
over in the Figma Plugin, or in the core-api backend?

Maybe a quick hack we could do is splitting the text based on the indices we get
back, then add children to the text object, even though it's not part of the official
API?

Let's give it a shot! We can't really do that right now with the current solution
of serializing the figma nodes to normal JS objects. We're gonna have to end up
supporting it directly in figcup instead...
