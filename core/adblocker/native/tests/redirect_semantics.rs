//! Pins what adblock-rust's official full result says for redirect, removeparam and exception rules,
//! so the app's boolean `should_block()` decision is documented rather than assumed.
//! Host-only: no WebView, no resources.json. Documents current behavior; it is not a feature.
use adblock::request::Request;
use adblock::resources::{MimeType, Resource, ResourceType};
use adblock::Engine;

const LIST: &str = "||ads.example.com/x.js^$script,redirect=noop.js\n\
||trk.example.com^$removeparam=utm_source\n\
||both.example.com^$script,redirect=noop.js\n\
@@||both.example.com/ok.js^$script\n\
||only-redirect-rule.example.com^$script,redirect-rule=noop.js\n";

fn check(engine: &Engine, url: &str, kind: &str) -> adblock::blocker::BlockerResult {
    let request = Request::new(url, "https://page.example/", kind, "GET").unwrap();
    engine.check_network_request(&request)
}

fn engine(with_resources: bool) -> Engine {
    let mut engine = Engine::new_with_list_text(LIST);
    if with_resources {
        engine.use_resources(vec![Resource {
            name: "noop.js".into(),
            aliases: vec![],
            kind: ResourceType::Mime(MimeType::ApplicationJavascript),
            content: "KGZ1bmN0aW9uKCl7fSkoKTs=".into(),
            dependencies: vec![],
            permission: Default::default(),
        }]);
    }
    engine
}

#[test]
fn redirect_rule_blocks_with_or_without_resources() {
    let without = check(&engine(false), "https://ads.example.com/x.js", "script");
    assert!(without.should_block() && without.redirect.is_none());
    let with = check(&engine(true), "https://ads.example.com/x.js", "script");
    assert!(with.should_block() && with.redirect.is_some());
}

#[test]
fn removeparam_rewrites_but_does_not_block() {
    for with_resources in [false, true] {
        let result = check(&engine(with_resources), "https://trk.example.com/p?utm_source=a&k=1", "xmlhttprequest");
        assert!(!result.should_block());
        assert_eq!(result.rewritten_url.as_deref(), Some("https://trk.example.com/p?k=1"));
    }
}

#[test]
fn exception_rule_beats_redirect_rule() {
    for with_resources in [false, true] {
        let e = engine(with_resources);
        assert!(check(&e, "https://both.example.com/z.js", "script").should_block());
        assert!(!check(&e, "https://both.example.com/ok.js", "script").should_block());
    }
}

#[test]
fn redirect_rule_alone_does_not_block() {
    for with_resources in [false, true] {
        let e = engine(with_resources);
        assert!(!check(&e, "https://only-redirect-rule.example.com/z.js", "script").should_block());
    }
}
