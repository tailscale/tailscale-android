// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"strings"
	"time"

	"gioui.org/f32"
	"gioui.org/font/opentype"
	"gioui.org/io/pointer"
	"gioui.org/io/system"
	"gioui.org/layout"
	"gioui.org/op"
	"gioui.org/op/clip"
	"gioui.org/op/paint"
	"gioui.org/text"
	"gioui.org/unit"
	"gioui.org/widget"
	"gioui.org/widget/material"
	"golang.org/x/exp/shiny/materialdesign/icons"
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"

	"eliasnaur.com/font/roboto/robotoregular"

	_ "image/png"
)

const enableGoogleSignin = false

type UI struct {
	theme *material.Theme
	store *stateStore

	// root is the scrollable list of the main UI.
	root layout.List
	// enabled is the switch for enabling or disabling the VPN.
	enabled widget.Bool
	search  widget.Editor

	// webSigin is the button for the web-based sign-in flow.
	webSignin widget.Clickable

	// googleSignin is the button for native Google Sign-in
	googleSignin widget.Clickable

	self  widget.Clickable
	peers []widget.Clickable

	intro struct {
		start widget.Clickable
		show  bool
	}

	menu struct {
		open    widget.Clickable
		dismiss Dismiss
		show    bool

		copy   widget.Clickable
		reauth widget.Clickable
		logout widget.Clickable
	}

	// The current pop-up message, if any
	message struct {
		text string
		// t0 is the time when the most recent message appeared.
		t0 time.Time
	}

	icons struct {
		search *widget.Icon
		more   *widget.Icon
		logo   paint.ImageOp
		google paint.ImageOp
	}

	events []UIEvent
}

// An UIPeer is either a peer or a section header
// with the user information.
type UIPeer struct {
	// Owner of the peer.
	Owner tailcfg.UserID
	// Name is the owner's name in all caps (for section headers).
	Name string
	// Peer is nil for section headers.
	Peer *tailcfg.Node
}

const (
	headerColor = 0x496495
	infoColor   = 0x3a517b
	white       = 0xffffff
	signinColor = 0xe1e3e4
)

const (
	keyShowIntro = "ui.showintro"
)

type (
	C = layout.Context
	D = layout.Dimensions
)

func newUI(store *stateStore) (*UI, error) {
	searchIcon, err := widget.NewIcon(icons.ActionSearch)
	if err != nil {
		return nil, err
	}
	moreIcon, err := widget.NewIcon(icons.NavigationMoreVert)
	if err != nil {
		return nil, err
	}
	logoData, err := tailscalePngBytes()
	if err != nil {
		return nil, err
	}
	logo, _, err := image.Decode(bytes.NewReader(logoData))
	if err != nil {
		return nil, err
	}
	googleData, err := googlePngBytes()
	if err != nil {
		return nil, err
	}
	google, _, err := image.Decode(bytes.NewReader(googleData))
	if err != nil {
		return nil, err
	}
	face, err := opentype.Parse(robotoregular.TTF)
	if err != nil {
		panic(fmt.Sprintf("failed to parse font: %v", err))
	}
	fonts := []text.FontFace{{Font: text.Font{Typeface: "Roboto"}, Face: face}}
	ui := &UI{
		theme: material.NewTheme(fonts),
		store: store,
	}
	ui.intro.show, _ = store.ReadBool(keyShowIntro, true)
	ui.icons.search = searchIcon
	ui.icons.more = moreIcon
	ui.icons.logo = paint.NewImageOp(logo)
	ui.icons.google = paint.NewImageOp(google)
	ui.icons.more.Color = rgb(white)
	ui.icons.search.Color = ui.theme.Color.Hint
	ui.root.Axis = layout.Vertical
	ui.search.SingleLine = true
	return ui, nil
}

func (ui *UI) layout(gtx layout.Context, sysIns system.Insets, state *clientState) []UIEvent {
	ui.events = nil
	if ui.enabled.Changed() {
		ui.events = append(ui.events, ConnectEvent{Enable: ui.enabled.Value})
	}

	for _, e := range ui.search.Events() {
		if _, ok := e.(widget.ChangeEvent); ok {
			ui.events = append(ui.events, SearchEvent{Query: ui.search.Text()})
			break
		}
	}
	for ui.menu.open.Clicked() {
		ui.menu.show = !ui.menu.show
	}

	netmap := state.net.NetworkMap
	var localName, localAddr string
	var expiry time.Time
	if netmap != nil {
		expiry = netmap.Expiry
		localName = netmap.Hostinfo.Hostname
		if addrs := netmap.Addresses; len(addrs) > 0 {
			localAddr = addrs[0].IP.String()
		}
	}

	if ui.googleSignin.Clicked() {
		ui.events = append(ui.events, GoogleAuthEvent{})
	}

	if ui.webSignin.Clicked() {
		ui.events = append(ui.events, ReauthEvent{})
	}

	if ui.menuClicked(&ui.menu.copy) && localAddr != "" {
		ui.copyAddress(gtx, localAddr)
	}

	if ui.menuClicked(&ui.menu.reauth) {
		ui.events = append(ui.events, ReauthEvent{})
	}

	if ui.menuClicked(&ui.menu.logout) {
		ui.events = append(ui.events, LogoutEvent{})
	}

	for len(ui.peers) < len(state.Peers) {
		ui.peers = append(ui.peers, widget.Clickable{})
	}
	if max := len(state.Peers); len(ui.peers) > max {
		ui.peers = ui.peers[:max]
	}

	const numHeaders = 5
	n := numHeaders + len(state.Peers)
	needsLogin := state.net.State == ipn.NeedsLogin
	ui.root.Layout(gtx, n, func(gtx C, idx int) D {
		var in layout.Inset
		if idx == n-1 {
			// The last list element includes the bottom system
			// inset.
			in.Bottom = sysIns.Bottom
		}
		return in.Layout(gtx, func(gtx C) D {
			switch idx {
			case 0:
				return ui.layoutTop(gtx, sysIns, &state.net)
			case 1:
				if netmap == nil || state.net.State < ipn.Stopped {
					return D{}
				}
				return ui.layoutLocal(gtx, sysIns, localName, localAddr)
			case 2:
				if state.net.State < ipn.Stopped {
					return D{}
				}
				return ui.layoutSearchbar(gtx, sysIns)
			case 3:
				if !needsLogin {
					return D{}
				}
				return ui.layoutSignIn(gtx)
			case 4:
				if needsLogin || !state.net.LostInternet {
					return D{}
				}
				return ui.layoutDisconnected(gtx)
			default:
				if needsLogin {
					return D{}
				}
				pidx := idx - numHeaders
				p := &state.Peers[pidx]
				if p.Peer == nil {
					name := p.Name
					if p.Owner == netmap.User {
						name = "MY DEVICES"
					}
					return ui.layoutSection(gtx, sysIns, name)
				} else {
					clk := &ui.peers[pidx]
					return ui.layoutPeer(gtx, sysIns, p, clk)
				}
			}
		})
	})

	// "Copied" message.
	ui.layoutMessage(gtx, sysIns)

	// 3-dots menu.
	if ui.menu.show {
		ui.layoutMenu(gtx, sysIns, expiry)
	}

	// "Get started".
	if ui.intro.show {
		if ui.intro.start.Clicked() {
			ui.store.WriteBool(keyShowIntro, false)
			ui.intro.show = false
		}
		ui.layoutIntro(gtx)
	}

	return ui.events
}

// Dismiss is a widget that detects pointer presses.
type Dismiss struct {
}

func (d *Dismiss) Add(gtx layout.Context) {
	defer op.Push(gtx.Ops).Pop()
	pointer.Rect(image.Rectangle{Max: gtx.Constraints.Min}).Add(gtx.Ops)
	pointer.InputOp{Tag: d, Types: pointer.Press}.Add(gtx.Ops)
}

func (d *Dismiss) Dismissed(gtx layout.Context) bool {
	for _, e := range gtx.Events(d) {
		if e, ok := e.(pointer.Event); ok {
			if e.Type == pointer.Press {
				return true
			}
		}
	}
	return false
}

// layoutSignIn lays out the sign in button(s).
func (ui *UI) layoutSignIn(gtx layout.Context) layout.Dimensions {
	return layout.Inset{Top: unit.Dp(48), Left: unit.Dp(48), Right: unit.Dp(48)}.Layout(gtx, func(gtx C) D {
		return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
			layout.Rigid(func(gtx C) D {
				if !enableGoogleSignin {
					return D{}
				}
				signin := material.ButtonLayout(ui.theme, &ui.googleSignin)
				//signin.Background = rgb(headerColor)
				signin.Background = rgb(signinColor)

				return layout.Inset{Bottom: unit.Dp(16)}.Layout(gtx, func(gtx C) D {
					return signin.Layout(gtx, func(gtx C) D {
						gtx.Constraints.Max.Y = gtx.Px(unit.Dp(48))
						return layout.Flex{Alignment: layout.Middle}.Layout(gtx,
							layout.Rigid(func(gtx C) D {
								return layout.Inset{Right: unit.Dp(4)}.Layout(gtx, func(gtx C) D {
									return drawImage(gtx, ui.icons.google, unit.Dp(16))
								})
							}),
							layout.Rigid(func(gtx C) D {
								return layout.Inset{Top: unit.Dp(10), Bottom: unit.Dp(10)}.Layout(gtx, func(gtx C) D {
									l := material.Body2(ui.theme, "Sign in with Google")
									l.Color = ui.theme.Color.Text
									return l.Layout(gtx)
								})
							}),
						)
					})
				})
			}),
			layout.Rigid(func(gtx C) D {
				label := "Sign in with other"
				if !enableGoogleSignin {
					label = "Sign in"
				}
				signin := material.Button(ui.theme, &ui.webSignin, label)
				signin.Background = rgb(signinColor)
				signin.Color = ui.theme.Color.Text
				return signin.Layout(gtx)
			}),
		)
	})
}

// layoutDisconnected lays out the "please connect to the internet"
// message.
func (ui *UI) layoutDisconnected(gtx layout.Context) layout.Dimensions {
	return layout.UniformInset(unit.Dp(16)).Layout(gtx, func(gtx C) D {
		return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
			layout.Rigid(func(gtx C) D {
				return layout.Inset{Top: unit.Dp(8)}.Layout(gtx, func(gtx C) D {
					title := material.H6(ui.theme, "No internet connection")
					title.Alignment = text.Middle
					return title.Layout(gtx)
				})
			}),
			layout.Rigid(func(gtx C) D {
				return layout.Inset{Top: unit.Dp(8)}.Layout(gtx, func(gtx C) D {
					msg := material.Body2(ui.theme, "Tailscale is paused while your device is offline. Please reconnect to the internet.")
					msg.Alignment = text.Middle
					return msg.Layout(gtx)
				})
			}),
		)
	})
}

// layoutIntro lays out the intro page with the logo and terms.
func (ui *UI) layoutIntro(gtx layout.Context) {
	fill{rgb(0x232323)}.Layout(gtx, gtx.Constraints.Max)
	layout.Flex{Axis: layout.Vertical}.Layout(gtx,
		// 9 dot logo.
		layout.Rigid(func(gtx C) D {
			return layout.Inset{Top: unit.Dp(80), Bottom: unit.Dp(48)}.Layout(gtx, func(gtx C) D {
				return layout.N.Layout(gtx, func(gtx C) D {
					sz := gtx.Px(unit.Dp(72))
					drawLogo(gtx.Ops, sz)
					return layout.Dimensions{Size: image.Pt(sz, sz)}
				})
			})
		}),
		// "tailscale".
		layout.Rigid(func(gtx C) D {
			return layout.N.Layout(gtx, func(gtx C) D {
				return drawImage(gtx, ui.icons.logo, unit.Dp(200))
			})
		}),
		// Terms.
		layout.Rigid(func(gtx C) D {
			return layout.Inset{
				Top:   unit.Dp(48),
				Left:  unit.Dp(32),
				Right: unit.Dp(32),
			}.Layout(gtx, func(gtx C) D {
				terms := material.Body2(ui.theme, termsText)
				terms.Color = rgb(0xbfbfbf)
				terms.Alignment = text.Middle
				return terms.Layout(gtx)
			})
		}),
		// "Get started".
		layout.Rigid(func(gtx C) D {
			return layout.UniformInset(unit.Dp(16)).Layout(gtx, func(gtx C) D {
				start := material.Button(ui.theme, &ui.intro.start, "Get Started")
				start.Inset = layout.UniformInset(unit.Dp(16))
				start.CornerRadius = unit.Dp(16)
				start.Background = rgb(0x496495)
				start.TextSize = unit.Sp(20)
				return start.Layout(gtx)
			})
		}),
	)
}

// menuClicked is like btn.Clicked, but also closes the menu if true.
func (ui *UI) menuClicked(btn *widget.Clickable) bool {
	cl := btn.Clicked()
	if cl {
		ui.menu.show = false
	}
	return cl
}

// layoutMenu lays out the menu activated by the 3 dots button.
func (ui *UI) layoutMenu(gtx layout.Context, sysIns system.Insets, expiry time.Time) {
	ui.menu.dismiss.Add(gtx)
	if ui.menu.dismiss.Dismissed(gtx) {
		ui.menu.show = false
	}
	layout.Inset{
		Top:   unit.Add(gtx.Metric, sysIns.Top, unit.Dp(2)),
		Right: unit.Add(gtx.Metric, sysIns.Right, unit.Dp(2)),
	}.Layout(gtx, func(gtx C) D {
		return layout.NE.Layout(gtx, func(gtx C) D {
			return Background{Color: argb(0x33000000), CornerRadius: unit.Dp(2)}.Layout(gtx, func(gtx C) D {
				return layout.UniformInset(unit.Px(1)).Layout(gtx, func(gtx C) D {
					return Background{Color: rgb(0xfafafa), CornerRadius: unit.Px(4)}.Layout(gtx, func(gtx C) D {
						menu := &ui.menu
						items := []struct {
							btn   *widget.Clickable
							title string
						}{
							{title: "Copy My IP Address", btn: &menu.copy},
							{title: "Reauthenticate", btn: &menu.reauth},
							{title: "Log out", btn: &menu.logout},
						}
						// Lay out menu items twice; once for
						// measuring the widest item, once for actual layout.
						var maxWidth int
						var minWidth int
						children := []layout.FlexChild{
							layout.Rigid(func(gtx C) D {
								return layout.Inset{
									Top:    unit.Dp(16),
									Right:  unit.Dp(16),
									Left:   unit.Dp(16),
									Bottom: unit.Dp(4),
								}.Layout(gtx, func(gtx C) D {
									gtx.Constraints.Min.X = minWidth
									var expiryStr string
									const fmtStr = time.Stamp
									switch {
									case expiry.IsZero():
										expiryStr = "Expires: (never)"
									case time.Now().After(expiry):
										expiryStr = fmt.Sprintf("Expired: %s", expiry.Format(fmtStr))
									default:
										expiryStr = fmt.Sprintf("Expires: %s", expiry.Format(fmtStr))
									}
									l := material.Caption(ui.theme, expiryStr)
									l.Color = rgb(0x8f8f8f)
									dims := l.Layout(gtx)
									if w := dims.Size.X; w > maxWidth {
										maxWidth = w
									}
									return dims
								})
							}),
						}
						for i := 0; i < len(items); i++ {
							it := &items[i]
							children = append(children, layout.Rigid(func(gtx C) D {
								return material.Clickable(gtx, it.btn, func(gtx C) D {
									return layout.UniformInset(unit.Dp(16)).Layout(gtx, func(gtx C) D {
										gtx.Constraints.Min.X = minWidth
										dims := material.Body1(ui.theme, it.title).Layout(gtx)
										if w := dims.Size.X; w > maxWidth {
											maxWidth = w
										}
										return dims
									})
								})
							}))
						}
						f := layout.Flex{Axis: layout.Vertical}
						// First pass: record and discard operations
						// and determine widest item.
						m := op.Record(gtx.Ops)
						f.Layout(gtx, children...)
						m.Stop()
						// Second pass: layout items with equal width.
						minWidth = maxWidth
						return f.Layout(gtx, children...)
					})
				})
			})
		})
	})
}

func (ui *UI) layoutMessage(gtx layout.Context, sysIns system.Insets) layout.Dimensions {
	s := ui.message.text
	if s == "" {
		return D{}
	}
	now := gtx.Now
	d := now.Sub(ui.message.t0)
	rem := 4*time.Second - d
	if rem < 0 {
		return D{}
	}
	op.InvalidateOp{At: now.Add(rem)}.Add(gtx.Ops)
	return layout.S.Layout(gtx, func(gtx C) D {
		return layout.Inset{Bottom: unit.Add(gtx.Metric, sysIns.Bottom, unit.Dp(8))}.Layout(gtx, func(gtx C) D {
			return Background{Color: rgb(0x323232), CornerRadius: unit.Dp(5)}.Layout(gtx, func(gtx C) D {
				return layout.UniformInset(unit.Dp(12)).Layout(gtx, func(gtx C) D {
					l := material.Body2(ui.theme, s)
					l.Color = rgb(0xdddddd)
					return l.Layout(gtx)
				})
			})
		})
	})
}

func (ui *UI) showMessage(gtx layout.Context, msg string) {
	ui.message.text = msg
	ui.message.t0 = gtx.Now
	op.InvalidateOp{}.Add(gtx.Ops)
}

// layoutPeer lays out a peer name and IP address (e.g.
// "localhost\n100.100.100.101")
func (ui *UI) layoutPeer(gtx layout.Context, sysIns system.Insets, p *UIPeer, clk *widget.Clickable) layout.Dimensions {
	for clk.Clicked() {
		if addrs := p.Peer.Addresses; len(addrs) > 0 {
			ui.copyAddress(gtx, addrs[0].IP.String())
		}
	}
	return material.Clickable(gtx, clk, func(gtx C) D {
		return layout.Inset{
			Top:    unit.Dp(8),
			Right:  unit.Max(gtx.Metric, sysIns.Right, unit.Dp(16)),
			Left:   unit.Max(gtx.Metric, sysIns.Left, unit.Dp(16)),
			Bottom: unit.Dp(8),
		}.Layout(gtx, func(gtx C) D {
			gtx.Constraints.Min.X = gtx.Constraints.Max.X
			return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
				layout.Rigid(func(gtx C) D {
					return layout.Inset{Bottom: unit.Dp(4)}.Layout(gtx, func(gtx C) D {
						name := p.Peer.Hostinfo.Hostname
						if name == "" {
							name = p.Peer.ID.String()
						}
						return material.H6(ui.theme, name).Layout(gtx)
					})
				}),
				layout.Rigid(func(gtx C) D {
					var addrs []string
					for _, addr := range p.Peer.Addresses {
						addrs = append(addrs, addr.IP.String())
					}
					l := material.Body2(ui.theme, strings.Join(addrs, ","))
					l.Color = rgb(0x434343)
					return l.Layout(gtx)
				}),
			)
		})
	})
}

// layoutSection lays out a section title (e.g. "My devices").
func (ui *UI) layoutSection(gtx layout.Context, sysIns system.Insets, title string) layout.Dimensions {
	return Background{Color: rgb(0xe1e0e9)}.Layout(gtx, func(gtx C) D {
		return layout.Inset{
			Top:    unit.Dp(16),
			Right:  unit.Max(gtx.Metric, sysIns.Right, unit.Dp(16)),
			Left:   unit.Max(gtx.Metric, sysIns.Left, unit.Dp(16)),
			Bottom: unit.Dp(16),
		}.Layout(gtx, func(gtx C) D {
			l := material.Body1(ui.theme, title)
			l.Color = rgb(0x6f797d)
			return l.Layout(gtx)
		})
	})
}

// layoutTop lays out the top controls: toggle, status and menu dots.
func (ui *UI) layoutTop(gtx layout.Context, sysIns system.Insets, state *NetworkState) layout.Dimensions {
	in := layout.Inset{
		Top:    unit.Dp(16),
		Bottom: unit.Dp(16),
	}
	return Background{Color: rgb(headerColor)}.Layout(gtx, func(gtx C) D {
		return layout.Inset{
			Top:   sysIns.Top,
			Right: unit.Max(gtx.Metric, sysIns.Right, unit.Dp(8)),
			Left:  unit.Max(gtx.Metric, sysIns.Left, unit.Dp(16)),
		}.Layout(gtx, func(gtx C) D {
			return layout.Flex{Alignment: layout.Middle}.Layout(gtx,
				layout.Rigid(func(gtx C) D {
					return in.Layout(gtx, func(gtx C) D {
						sw := material.Switch(ui.theme, &ui.enabled)
						sw.Color.Enabled = rgb(white)
						if state.State < ipn.Stopped {
							sw.Color.Enabled = rgb(0xbbbbbb)
							sw.Color.Disabled = rgb(0xbbbbbb)
						}
						return sw.Layout(gtx)
					})
				}),
				layout.Flexed(1, func(gtx C) D {
					return in.Layout(gtx, func(gtx C) D {
						return layout.Inset{Left: unit.Dp(16)}.Layout(gtx, func(gtx C) D {
							lbl := material.Body1(ui.theme, statusString(state.State))
							lbl.Color = rgb(0xffffff)
							return lbl.Layout(gtx)
						})
					})
				}),
				layout.Rigid(func(gtx C) D {
					if state.State <= ipn.NeedsLogin {
						return D{}
					}
					return material.Clickable(gtx, &ui.menu.open, func(gtx C) D {
						return layout.UniformInset(unit.Dp(8)).Layout(gtx, func(gtx C) D {
							return ui.icons.more.Layout(gtx, unit.Dp(24))
						})
					})
				}),
			)
		})
	})
}

func statusString(state ipn.State) string {
	switch state {
	case ipn.Stopped:
		return "Stopped"
	case ipn.Starting:
		return "Starting..."
	case ipn.Running:
		return "Active"
	case ipn.NeedsMachineAuth:
		return "Awaiting Approval"
	case ipn.NeedsLogin:
		return "Needs Authentication"
	default:
		return "Loading..."
	}
}

func (ui *UI) copyAddress(gtx layout.Context, addr string) {
	ui.events = append(ui.events, CopyEvent{Text: addr})
	ui.showMessage(gtx, fmt.Sprintf("Copied %s", addr))
}

// layoutLocal lays out the information box about the local node's
// name and IP address.
func (ui *UI) layoutLocal(gtx layout.Context, sysIns system.Insets, host, addr string) layout.Dimensions {
	for ui.self.Clicked() {
		ui.copyAddress(gtx, addr)
	}
	return Background{Color: rgb(headerColor)}.Layout(gtx, func(gtx C) D {
		return layout.Inset{
			Right:  unit.Max(gtx.Metric, sysIns.Right, unit.Dp(8)),
			Left:   unit.Max(gtx.Metric, sysIns.Left, unit.Dp(8)),
			Bottom: unit.Dp(8),
		}.Layout(gtx, func(gtx C) D {
			return Background{Color: rgb(infoColor), CornerRadius: unit.Dp(8)}.Layout(gtx, func(gtx C) D {
				return material.Clickable(gtx, &ui.self, func(gtx C) D {
					return layout.UniformInset(unit.Dp(16)).Layout(gtx, func(gtx C) D {
						gtx.Constraints.Min.X = gtx.Constraints.Max.X
						return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
							layout.Rigid(func(gtx C) D {
								return layout.Inset{Bottom: unit.Dp(4)}.Layout(gtx, func(gtx C) D {
									name := material.H6(ui.theme, host)
									name.Color = ui.theme.Color.InvText
									return name.Layout(gtx)
								})
							}),
							layout.Rigid(func(gtx C) D {
								name := material.Body2(ui.theme, addr)
								name.Color = rgb(0xc5ccd9)
								return name.Layout(gtx)
							}),
						)
					})
				})
			})
		})
	})
}

func (ui *UI) layoutSearchbar(gtx layout.Context, sysIns system.Insets) layout.Dimensions {
	return Background{Color: rgb(0xf0eff6)}.Layout(gtx, func(gtx C) D {
		return layout.Inset{
			Top:    unit.Dp(8),
			Right:  unit.Max(gtx.Metric, sysIns.Right, unit.Dp(8)),
			Left:   unit.Max(gtx.Metric, sysIns.Left, unit.Dp(8)),
			Bottom: unit.Dp(8),
		}.Layout(gtx, func(gtx C) D {
			return Background{Color: rgb(0xe3e2ea), CornerRadius: unit.Dp(8)}.Layout(gtx, func(gtx C) D {
				return layout.UniformInset(unit.Dp(8)).Layout(gtx, func(gtx C) D {
					return layout.Flex{Alignment: layout.Middle}.Layout(gtx,
						layout.Rigid(func(gtx C) D {
							return ui.icons.search.Layout(gtx, unit.Dp(24))
						}),
						layout.Flexed(1,
							material.Editor(ui.theme, &ui.search, "Search by hostname...").Layout,
						),
					)
				})
			})
		})
	})
}

// drawLogo draws the Tailscale logo using vector operations.
func drawLogo(ops *op.Ops, size int) {
	scale := float32(size) / 680
	discDia := 170 * scale
	off := 172 * 1.5 * scale
	tx := op.Offset(f32.Pt(off, 0))
	ty := op.Offset(f32.Pt(0, off))

	st := op.Push(ops)
	defer st.Pop()

	// First row of discs.
	row := op.Push(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	row.Pop()

	ty.Add(ops)
	// Second row.
	row = op.Push(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	row.Pop()

	ty.Add(ops)
	// Third row.
	row = op.Push(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	row.Pop()
}

func drawImage(gtx layout.Context, img paint.ImageOp, size unit.Value) layout.Dimensions {
	img.Add(gtx.Ops)
	sz := img.Size()
	aspect := float32(sz.Y) / float32(sz.X)
	w := gtx.Px(size)
	h := int(float32(w)*aspect + .5)
	paint.PaintOp{Rect: f32.Rectangle{Max: f32.Pt(float32(w), float32(h))}}.Add(gtx.Ops)
	return layout.Dimensions{Size: image.Pt(w, h)}
}

func drawDisc(ops *op.Ops, radius float32, col color.RGBA) {
	defer op.Push(ops).Pop()
	r2 := radius * .5
	dr := f32.Rectangle{Max: f32.Pt(radius, radius)}
	clip.Rect{
		Rect: dr,
		NE:   r2, NW: r2, SE: r2, SW: r2,
	}.Op(ops).Add(ops)
	paint.ColorOp{Color: col}.Add(ops)
	paint.PaintOp{Rect: dr}.Add(ops)
}

// background lays out a widget and draws a color background behind
// it.
type Background struct {
	Color        color.RGBA
	CornerRadius unit.Value
}

func (b Background) Layout(gtx layout.Context, w layout.Widget) layout.Dimensions {
	m := op.Record(gtx.Ops)
	dims := w(gtx)
	sz := dims.Size
	call := m.Stop()
	// Clip corners, if any.
	if r := gtx.Px(b.CornerRadius); r > 0 {
		rr := float32(r)
		clip.Rect{
			Rect: f32.Rectangle{Max: f32.Point{
				X: float32(sz.X),
				Y: float32(sz.Y),
			}},
			NE: rr, NW: rr, SE: rr, SW: rr,
		}.Op(gtx.Ops).Add(gtx.Ops)
	}
	fill{b.Color}.Layout(gtx, sz)
	call.Add(gtx.Ops)
	return dims
}

type fill struct {
	col color.RGBA
}

func (f fill) Layout(gtx layout.Context, sz image.Point) layout.Dimensions {
	defer op.Push(gtx.Ops).Pop()
	dr := f32.Rectangle{Max: layout.FPt(sz)}
	paint.ColorOp{Color: f.col}.Add(gtx.Ops)
	paint.PaintOp{Rect: dr}.Add(gtx.Ops)
	return layout.Dimensions{Size: sz}
}

func rgb(c uint32) color.RGBA {
	return argb((0xff << 24) | c)
}

func argb(c uint32) color.RGBA {
	return color.RGBA{A: uint8(c >> 24), R: uint8(c >> 16), G: uint8(c >> 8), B: uint8(c)}
}

const termsText = `Tailscale is a mesh VPN for securely connecting your devices. All connections are device-to-device, so we never see your data.

We collect and use your email address and name, as well as your device name, OS version, and IP address in order to help you to connect your devices and manage your settings. We log when you are connected to your network.`
