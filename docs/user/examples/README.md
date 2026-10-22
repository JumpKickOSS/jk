# Examples

Runnable samples of every kind live in [JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples),
organised from a first project (`01-start/`) to a production application (`06-production/`).

This directory keeps one sample that has to sit beside jk's sources:

| Sample | Shows |
|--------|--------|
| [third-party-plugin](third-party-plugin/) | A build plugin compiled against the published `cc.jumpkick:jk-plugin-sdk` coordinate |

`third-party-plugin` locks its SDK coordinate against `jumpkick.build/repo`;
`ThirdPartyPluginExampleTest` relocks a copy against the SDK the checkout built.
