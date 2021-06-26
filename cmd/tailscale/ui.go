// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
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
	"inet.af/netaddr"
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"

	_ "embed"

	"eliasnaur.com/font/roboto/robotobold"
	"eliasnaur.com/font/roboto/robotoregular"

	_ "image/png"
)

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

	// googleSignin is the button for native Google Sign-in.
	googleSignin widget.Clickable

	// openExitDialog opens the exit node picker.
	openExitDialog widget.Clickable

	signinType signinType

	self  widget.Clickable
	peers []widget.Clickable

	// exitDialog is state for the exit node dialog.
	exitDialog struct {
		show    bool
		dismiss Dismiss
		exits   widget.Enum
		list    layout.List
	}

	intro struct {
		list  layout.List
		start widget.Clickable
		show  bool
	}

	menu struct {
		open    widget.Clickable
		dismiss Dismiss
		show    bool

		copy   widget.Clickable
		reauth widget.Clickable
		exits  widget.Clickable
		logout widget.Clickable
	}

	// The current pop-up message, if any
	message struct {
		text string
		// t0 is the time when the most recent message appeared.
		t0 time.Time
	}

	icons struct {
		search     *widget.Icon
		more       *widget.Icon
		exitStatus *widget.Icon
		logo       paint.ImageOp
		google     paint.ImageOp
	}
}

type signinType uint8

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

// menuItem describes an item in a popup menu.
type menuItem struct {
	title string
	btn   *widget.Clickable
}

const (
	headerColor = 0x496495
	infoColor   = 0x3a517b
	white       = 0xffffff
)

const (
	keyShowIntro = "ui.showintro"
)

const (
	noSignin signinType = iota
	webSignin
	googleSignin
)

type (
	C = layout.Context
	D = layout.Dimensions
)

var (
	//go:embed tailscale.png
	tailscaleLogo []byte
	//go:embed google.png
	googleLogo []byte
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
	exitStatus, err := widget.NewIcon(icons.NavigationMenu)
	if err != nil {
		return nil, err
	}
	logo, _, err := image.Decode(bytes.NewReader(tailscaleLogo))
	if err != nil {
		return nil, err
	}
	google, _, err := image.Decode(bytes.NewReader(googleLogo))
	if err != nil {
		return nil, err
	}
	face, err := opentype.Parse(robotoregular.TTF)
	if err != nil {
		panic(fmt.Sprintf("failed to parse font: %v", err))
	}
	faceBold, err := opentype.Parse(robotobold.TTF)
	if err != nil {
		panic(fmt.Sprintf("failed to parse font: %v", err))
	}
	fonts := []text.FontFace{
		{Font: text.Font{Typeface: "Roboto"}, Face: face},
		{Font: text.Font{Typeface: "Roboto", Weight: text.Bold}, Face: faceBold},
	}
	ui := &UI{
		theme: material.NewTheme(fonts),
		store: store,
	}
	ui.intro.show, _ = store.ReadBool(keyShowIntro, true)
	ui.icons.search = searchIcon
	ui.icons.more = moreIcon
	ui.icons.exitStatus = exitStatus
	ui.icons.logo = paint.NewImageOp(logo)
	ui.icons.google = paint.NewImageOp(google)
	ui.icons.more.Color = rgb(white)
	ui.icons.search.Color = mulAlpha(ui.theme.Palette.Fg, 0xbb)
	ui.icons.exitStatus.Color = rgb(white)
	ui.root.Axis = layout.Vertical
	ui.intro.list.Axis = layout.Vertical
	ui.search.SingleLine = true
	ui.exitDialog.list.Axis = layout.Vertical
	return ui, nil
}

func mulAlpha(c color.NRGBA, alpha uint8) color.NRGBA {
	c.A = uint8(uint32(c.A) * uint32(alpha) / 0xff)
	return c
}

func (ui *UI) onBack() bool {
	if !ui.menu.show {
		return false
	}
	ui.menu.show = false
	return true
}

func (ui *UI) layout(gtx layout.Context, sysIns system.Insets, state *clientState) []UIEvent {
	var events []UIEvent
	if ui.enabled.Changed() {
		events = append(events, ConnectEvent{Enable: ui.enabled.Value})
	}

	for _, e := range ui.search.Events() {
		if _, ok := e.(widget.ChangeEvent); ok {
			events = append(events, SearchEvent{Query: ui.search.Text()})
			break
		}
	}
	for ui.menu.open.Clicked() {
		ui.menu.show = !ui.menu.show
	}

	netmap := state.backend.NetworkMap
	var (
		localName, localAddr string
		expiry               time.Time
		userID               tailcfg.UserID
		exitID               tailcfg.StableNodeID
	)
	if netmap != nil {
		userID = netmap.User
		expiry = netmap.Expiry
		localName = netmap.SelfNode.DisplayName(false)
		if addrs := netmap.Addresses; len(addrs) > 0 {
			localAddr = addrs[0].IP().String()
		}
	}
	if p := state.backend.Prefs; p != nil {
		exitID = p.ExitNodeID
	}
	if d := &ui.exitDialog; d.show {
		if newID := tailcfg.StableNodeID(d.exits.Value); newID != exitID {
			d.show = false
			events = append(events, RouteAllEvent{newID})
		}
	} else {
		d.exits.Value = string(exitID)
	}

	if ui.googleSignin.Clicked() {
		ui.signinType = googleSignin
		events = append(events, GoogleAuthEvent{})
	}

	if ui.webSignin.Clicked() {
		ui.signinType = webSignin
		events = append(events, WebAuthEvent{})
	}

	if ui.menuClicked(&ui.menu.copy) && localAddr != "" {
		events = append(events, CopyEvent{Text: localAddr})
		ui.showCopied(gtx, localAddr)
	}

	if ui.menuClicked(&ui.menu.reauth) {
		events = append(events, ReauthEvent{})
	}

	if ui.menuClicked(&ui.menu.exits) || ui.openExitDialog.Clicked() {
		ui.exitDialog.show = true
	}

	if ui.menuClicked(&ui.menu.logout) {
		events = append(events, LogoutEvent{})
	}

	for len(ui.peers) < len(state.Peers) {
		ui.peers = append(ui.peers, widget.Clickable{})
	}
	if max := len(state.Peers); len(ui.peers) > max {
		ui.peers = ui.peers[:max]
	}

	const numHeaders = 6
	n := numHeaders + len(state.Peers)
	needsLogin := state.backend.State == ipn.NeedsLogin
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
				return ui.layoutTop(gtx, sysIns, &state.backend)
			case 1:
				if netmap == nil || state.backend.State < ipn.Stopped {
					return D{}
				}
				for ui.self.Clicked() {
					events = append(events, CopyEvent{Text: localAddr})
					ui.showCopied(gtx, localAddr)
				}
				return ui.layoutLocal(gtx, sysIns, localName, localAddr)
			case 2:
				return ui.layoutExitStatus(gtx, &state.backend)
			case 3:
				if state.backend.State < ipn.Stopped {
					return D{}
				}
				return ui.layoutSearchbar(gtx, sysIns)
			case 4:
				if !needsLogin || state.backend.LostInternet {
					return D{}
				}
				return ui.layoutSignIn(gtx, &state.backend)
			case 5:
				if !state.backend.LostInternet {
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
					if p.Owner == userID {
						name = "MY DEVICES"
					}
					return ui.layoutSection(gtx, sysIns, name)
				} else {
					clk := &ui.peers[pidx]
					if clk.Clicked() {
						if addrs := p.Peer.Addresses; len(addrs) > 0 {
							a := addrs[0].IP().String()
							events = append(events, CopyEvent{Text: a})
							ui.showCopied(gtx, a)
						}
					}
					return ui.layoutPeer(gtx, sysIns, p, userID, clk)
				}
			}
		})
	})

	ui.layoutExitNodeDialog(gtx, sysIns, state.backend.Exits)

	// Popup messages.
	ui.layoutMessage(gtx, sysIns)

	// 3-dots menu.
	if ui.menu.show {
		ui.layoutMenu(gtx, sysIns, expiry, exitID != "" || len(state.backend.Exits) > 0)
	}

	// "Get started".
	if ui.intro.show {
		if ui.intro.start.Clicked() {
			ui.store.WriteBool(keyShowIntro, false)
			ui.intro.show = false
		}
		ui.layoutIntro(gtx, sysIns)
	}

	return events
}

func (ui *UI) ShowMessage(msg string) {
	ui.message.text = msg
	ui.message.t0 = time.Now()
}

// Dismiss is a widget that detects pointer presses.
type Dismiss struct {
}

func (d *Dismiss) Add(gtx layout.Context, color color.NRGBA) {
	defer op.Save(gtx.Ops).Load()
	pointer.Rect(image.Rectangle{Max: gtx.Constraints.Min}).Add(gtx.Ops)
	pointer.InputOp{Tag: d, Types: pointer.Press}.Add(gtx.Ops)
	paint.Fill(gtx.Ops, color)
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

func (ui *UI) layoutExitStatus(gtx layout.Context, state *BackendState) layout.Dimensions {
	var bg color.NRGBA
	var text string
	switch state.ExitStatus {
	case ExitNone:
		return D{}
	case ExitOffline:
		text = "Exit node offline"
		bg = rgb(0xc65835)
	case ExitOnline:
		text = "Using exit node"
		bg = rgb(0x338b51)
	}
	paint.Fill(gtx.Ops, bg)
	return material.Clickable(gtx, &ui.openExitDialog, func(gtx C) D {
		gtx.Constraints.Min.X = gtx.Constraints.Max.X
		return layout.Inset{
			Top:    unit.Dp(12),
			Bottom: unit.Dp(12),
			Right:  unit.Dp(24),
			Left:   unit.Dp(24),
		}.Layout(gtx, func(gtx C) D {
			return layout.Flex{Alignment: layout.Middle}.Layout(gtx,
				layout.Flexed(1, func(gtx C) D {
					return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
						layout.Rigid(func(gtx C) D {
							lbl := material.Body2(ui.theme, text)
							lbl.Color = rgb(white)
							return lbl.Layout(gtx)
						}),
						layout.Rigid(func(gtx C) D {
							node := material.Body2(ui.theme, state.Exit.Label)
							node.Color = argb(0x88ffffff)
							return node.Layout(gtx)
						}),
					)
				}),
				layout.Rigid(func(gtx C) D {
					return ui.icons.exitStatus.Layout(gtx, unit.Dp(24))
				}),
			)
		})
	})
}

// layoutSignIn lays out the sign in button(s).
func (ui *UI) layoutSignIn(gtx layout.Context, state *BackendState) layout.Dimensions {
	return layout.Inset{Top: unit.Dp(48), Left: unit.Dp(48), Right: unit.Dp(48)}.Layout(gtx, func(gtx C) D {
		const (
			textColor = 0x555555
		)

		border := widget.Border{Color: rgb(textColor), CornerRadius: unit.Dp(4), Width: unit.Px(1)}
		return layout.Flex{Axis: layout.Vertical, Alignment: layout.Middle}.Layout(gtx,
			layout.Rigid(func(gtx C) D {
				if !googleSignInEnabled() {
					return D{}
				}
				return layout.Inset{Bottom: unit.Dp(16)}.Layout(gtx, func(gtx C) D {
					signin := material.ButtonLayout(ui.theme, &ui.googleSignin)
					signin.Background = color.NRGBA{} // transparent

					return ui.withLoader(gtx, ui.signinType == googleSignin, func(gtx C) D {
						return border.Layout(gtx, func(gtx C) D {
							if ui.signinType != noSignin {
								gtx.Queue = nil
							}
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
											l.Color = rgb(textColor)
											return l.Layout(gtx)
										})
									}),
								)
							})
						})
					})
				})
			}),
			layout.Rigid(func(gtx C) D {
				label := "Sign in with other"
				if !googleSignInEnabled() {
					label = "Sign in"
				}
				return ui.withLoader(gtx, ui.signinType == webSignin, func(gtx C) D {
					return border.Layout(gtx, func(gtx C) D {
						if ui.signinType != noSignin {
							gtx.Queue = nil
						}
						signin := material.Button(ui.theme, &ui.webSignin, label)
						signin.Background = color.NRGBA{} // transparent
						signin.Color = rgb(textColor)
						return signin.Layout(gtx)
					})
				})
			}),
		)
	})
}

func (ui *UI) withLoader(gtx layout.Context, loading bool, w layout.Widget) layout.Dimensions {
	cons := gtx.Constraints
	return layout.Stack{Alignment: layout.W}.Layout(gtx,
		layout.Stacked(func(gtx C) D {
			gtx.Constraints = cons
			return w(gtx)
		}),
		layout.Stacked(func(gtx C) D {
			if !loading {
				return D{}
			}
			return layout.Inset{Left: unit.Dp(16)}.Layout(gtx, func(gtx C) D {
				gtx.Constraints.Min = image.Point{
					X: gtx.Px(unit.Dp(16)),
				}
				return material.Loader(ui.theme).Layout(gtx)
			})
		}),
	)
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
func (ui *UI) layoutIntro(gtx layout.Context, sysIns system.Insets) {
	fill{rgb(0x232323)}.Layout(gtx, gtx.Constraints.Max)
	ui.intro.list.Layout(gtx, 1, func(gtx C, idx int) D {
		return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
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
				return layout.Inset{
					Top:    unit.Dp(16),
					Left:   unit.Dp(16),
					Right:  unit.Dp(16),
					Bottom: unit.Add(gtx.Metric, sysIns.Bottom),
				}.Layout(gtx, func(gtx C) D {
					start := material.Button(ui.theme, &ui.intro.start, "Get Started")
					start.Inset = layout.UniformInset(unit.Dp(16))
					start.CornerRadius = unit.Dp(16)
					start.Background = rgb(0x496495)
					start.TextSize = unit.Sp(20)
					return start.Layout(gtx)
				})
			}),
		)
	})
}

// menuClicked is like btn.Clicked, but also closes the menu if true.
func (ui *UI) menuClicked(btn *widget.Clickable) bool {
	cl := btn.Clicked()
	if cl {
		ui.menu.show = false
	}
	return cl
}

// layoutExitNodeDialog lays out the exit node selection dialog. If the user changed the node,
// true is returned along with the node.
func (ui *UI) layoutExitNodeDialog(gtx layout.Context, sysIns system.Insets, exits []ExitNode) {
	d := &ui.exitDialog
	if d.dismiss.Dismissed(gtx) {
		d.show = false
	}
	if !d.show {
		return
	}
	d.dismiss.Add(gtx, argb(0x66000000))
	layout.Inset{
		Top:    unit.Add(gtx.Metric, sysIns.Top, unit.Dp(16)),
		Right:  unit.Add(gtx.Metric, sysIns.Right, unit.Dp(16)),
		Bottom: unit.Add(gtx.Metric, sysIns.Bottom, unit.Dp(16)),
		Left:   unit.Add(gtx.Metric, sysIns.Left, unit.Dp(16)),
	}.Layout(gtx, func(gtx C) D {
		return layout.Center.Layout(gtx, func(gtx C) D {
			gtx.Constraints.Min.X = gtx.Px(unit.Dp(250))
			gtx.Constraints.Max.X = gtx.Constraints.Min.X
			return layoutDialog(gtx, func(gtx C) D {
				return layout.Flex{Axis: layout.Vertical}.Layout(gtx,
					layout.Rigid(func(gtx C) D {
						// Header.
						return layout.Inset{
							Top:    unit.Dp(16),
							Right:  unit.Dp(20),
							Left:   unit.Dp(20),
							Bottom: unit.Dp(16),
						}.Layout(gtx, func(gtx C) D {
							l := material.Body1(ui.theme, "Use exit node...")
							l.Font.Weight = text.Bold
							return l.Layout(gtx)
						})
					}),
					layout.Flexed(1, func(gtx C) D {
						gtx.Constraints.Min.Y = 0
						// Add "none" exit node.
						n := len(exits) + 1
						return d.list.Layout(gtx, n, func(gtx C, idx int) D {
							switch idx {
							case n - 1:
							}
							node := ExitNode{Label: "None", Online: true}
							if idx >= 1 {
								node = exits[idx-1]
							}
							lbl := node.Label
							if !node.Online {
								lbl = lbl + " (offline)"
							}
							btn := material.RadioButton(ui.theme, &d.exits, string(node.ID), lbl)
							if !node.Online {
								btn.Color = rgb(0xbbbbbb)
								btn.IconColor = btn.Color
							}
							return layout.Inset{
								Right:  unit.Dp(16),
								Left:   unit.Dp(16),
								Bottom: unit.Dp(16),
							}.Layout(gtx, btn.Layout)
						})
					}),
				)
			})
		})
	})
}

func layoutMenu(th *material.Theme, gtx layout.Context, items []menuItem, header layout.Widget) layout.Dimensions {
	return layoutDialog(gtx, func(gtx C) D {
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
					dims := header(gtx)
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
						dims := material.Body1(th, it.title).Layout(gtx)
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
}

func layoutDialog(gtx layout.Context, w layout.Widget) layout.Dimensions {
	return widget.Border{Color: argb(0x33000000), CornerRadius: unit.Dp(2), Width: unit.Px(1)}.Layout(gtx, func(gtx C) D {
		return Background{Color: rgb(0xfafafa), CornerRadius: unit.Dp(2)}.Layout(gtx, w)
	})
}

// layoutMenu lays out the menu activated by the 3 dots button.
func (ui *UI) layoutMenu(gtx layout.Context, sysIns system.Insets, expiry time.Time, showExits bool) {
	ui.menu.dismiss.Add(gtx, color.NRGBA{})
	if ui.menu.dismiss.Dismissed(gtx) {
		ui.menu.show = false
	}
	layout.Inset{
		Top:   unit.Add(gtx.Metric, sysIns.Top, unit.Dp(2)),
		Right: unit.Add(gtx.Metric, sysIns.Right, unit.Dp(2)),
	}.Layout(gtx, func(gtx C) D {
		return layout.NE.Layout(gtx, func(gtx C) D {
			menu := &ui.menu
			items := []menuItem{
				{title: "Copy My IP Address", btn: &menu.copy},
			}
			if showExits {
				items = append(items, menuItem{title: "Use exit node...", btn: &menu.exits})
			}
			items = append(items,
				menuItem{title: "Reauthenticate", btn: &menu.reauth},
				menuItem{title: "Log out", btn: &menu.logout},
			)
			return layoutMenu(ui.theme, gtx, items, func(gtx C) D {
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
				return l.Layout(gtx)
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
func (ui *UI) layoutPeer(gtx layout.Context, sysIns system.Insets, p *UIPeer, user tailcfg.UserID, clk *widget.Clickable) layout.Dimensions {
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
						name := p.Peer.DisplayName(p.Peer.User == user)
						return material.H6(ui.theme, name).Layout(gtx)
					})
				}),
				layout.Rigid(func(gtx C) D {
					var bestIP netaddr.IP // IP to show; first IPv4, or first IPv6 if no IPv4
					for _, addr := range p.Peer.Addresses {
						if ip := addr.IP(); bestIP.IsZero() || bestIP.Is6() && ip.Is4() {
							bestIP = ip
						}
					}
					l := material.Body2(ui.theme, bestIP.String())
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
func (ui *UI) layoutTop(gtx layout.Context, sysIns system.Insets, state *BackendState) layout.Dimensions {
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
						if state.State <= ipn.NeedsLogin {
							return D{}
						}
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
					btn := material.IconButton(ui.theme, &ui.menu.open, ui.icons.more)
					btn.Background = color.NRGBA{}
					return btn.Layout(gtx)
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
		return "Tailscale"
	default:
		return "Loading..."
	}
}

func (ui *UI) showCopied(gtx layout.Context, addr string) {
	ui.showMessage(gtx, fmt.Sprintf("Copied %s", addr))
}

// layoutLocal lays out the information box about the local node's
// name and IP address.
func (ui *UI) layoutLocal(gtx layout.Context, sysIns system.Insets, host, addr string) layout.Dimensions {
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
									name.Color = rgb(0xffffff)
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
							material.Editor(ui.theme, &ui.search, "Search by machine name...").Layout,
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

	st := op.Save(ops)
	defer st.Load()

	// First row of discs.
	row := op.Save(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	row.Load()

	ty.Add(ops)
	// Second row.
	row = op.Save(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	row.Load()

	ty.Add(ops)
	// Third row.
	row = op.Save(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0xfffdfa))
	tx.Add(ops)
	drawDisc(ops, discDia, rgb(0x54514d))
	row.Load()
}

func drawImage(gtx layout.Context, img paint.ImageOp, size unit.Value) layout.Dimensions {
	defer op.Save(gtx.Ops).Load()
	img.Add(gtx.Ops)
	sz := img.Size()
	aspect := float32(sz.Y) / float32(sz.X)
	w := gtx.Px(size)
	h := int(float32(w)*aspect + .5)
	scale := float32(w) / float32(sz.X)
	op.Affine(f32.Affine2D{}.Scale(f32.Point{}, f32.Point{X: scale, Y: scale})).Add(gtx.Ops)
	paint.PaintOp{}.Add(gtx.Ops)
	return layout.Dimensions{Size: image.Pt(w, h)}
}

func drawDisc(ops *op.Ops, radius float32, col color.NRGBA) {
	defer op.Save(ops).Load()
	r2 := radius * .5
	dr := f32.Rectangle{Max: f32.Pt(radius, radius)}
	clip.RRect{
		Rect: dr,
		NE:   r2, NW: r2, SE: r2, SW: r2,
	}.Add(ops)
	paint.ColorOp{Color: col}.Add(ops)
	paint.PaintOp{}.Add(ops)
}

// Background lays out a widget and draws a color background behind it.
type Background struct {
	Color        color.NRGBA
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
		clip.RRect{
			Rect: f32.Rectangle{Max: f32.Point{
				X: float32(sz.X),
				Y: float32(sz.Y),
			}},
			NE: rr, NW: rr, SE: rr, SW: rr,
		}.Add(gtx.Ops)
	}
	fill{b.Color}.Layout(gtx, sz)
	call.Add(gtx.Ops)
	return dims
}

type fill struct {
	col color.NRGBA
}

func (f fill) Layout(gtx layout.Context, sz image.Point) layout.Dimensions {
	defer op.Save(gtx.Ops).Load()
	clip.Rect(image.Rectangle{Max: sz}).Add(gtx.Ops)
	paint.ColorOp{Color: f.col}.Add(gtx.Ops)
	paint.PaintOp{}.Add(gtx.Ops)
	return layout.Dimensions{Size: sz}
}

func rgb(c uint32) color.NRGBA {
	return argb((0xff << 24) | c)
}

func argb(c uint32) color.NRGBA {
	return color.NRGBA{A: uint8(c >> 24), R: uint8(c >> 16), G: uint8(c >> 8), B: uint8(c)}
}

const termsText = `Tailscale is a mesh VPN for securely connecting your devices. All connections are device-to-device, so we never see your data.

We collect and use your email address and name, as well as your device name, OS version, and IP address in order to help you to connect your devices and manage your settings. We log when you are connected to your network.`
