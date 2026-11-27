// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"encoding/xml"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const (
	strAccounts                 = "accounts"
	strAddAccount               = "add_account_short"
	strAddNewRoute              = "add_new_route"
	strAddProfileFailed         = "add_profile_failed_title"
	strAllowLANAccess           = "allow_lan_access"
	strAppName                  = "app_name"
	strAuthKeyExplanation       = "auth_key_explanation"
	strAuthKeyMenu              = "auth_key_menu"
	strChooseExitNode           = "choose_exit_node"
	strConnected                = "connected"
	strContinue                 = "_continue"
	strCustomControlDesc        = "custom_control_menu_desc"
	strCustomControlMenu        = "custom_control_menu"
	strDeleteRoute              = "delete_route"
	strDisable                  = "disable"
	strExitNode                 = "exit_node"
	strFailedToSave             = "failed_to_save"
	strGetStarted               = "getStarted"
	strLoginToJoin              = "login_to_join_your_tailnet"
	strNone                     = "none"
	strOK                       = "ok"
	strShare                    = "share"
	strSubnetRouting            = "subnet_routing"
	strTaildropDirPickerButton  = "taildrop_directory_picker_button"
	strTaildropFileReceived     = "taildrop_file_received"
	strTaildropSavedInDirectory = "taildrop_saved_in_directory"
	strTaildropShareFailedShort = "taildrop_share_failed_short"
	strTaildropShareFailedTitle = "taildrop_share_failed_title"
	strUseTailscaleSubnets      = "use_tailscale_subnets"
)

var smokeStrings = []string{
	strAccounts,
	strAddAccount,
	strAddNewRoute,
	strAddProfileFailed,
	strAllowLANAccess,
	strAppName,
	strAuthKeyExplanation,
	strAuthKeyMenu,
	strChooseExitNode,
	strConnected,
	strContinue,
	strCustomControlDesc,
	strCustomControlMenu,
	strDeleteRoute,
	strDisable,
	strExitNode,
	strFailedToSave,
	strGetStarted,
	strLoginToJoin,
	strNone,
	strOK,
	strShare,
	strSubnetRouting,
	strTaildropDirPickerButton,
	strTaildropFileReceived,
	strTaildropSavedInDirectory,
	strTaildropShareFailedShort,
	strTaildropShareFailedTitle,
	strUseTailscaleSubnets,
}

var androidStringEscapes = strings.NewReplacer(`\'`, `'`, `\"`, `"`, `\n`, "\n", `\\`, `\`)

func parseAppStrings(data []byte) (map[string]string, error) {
	var res struct {
		Strings []struct {
			Name  string `xml:"name,attr"`
			Value string `xml:",chardata"`
		} `xml:"string"`
	}
	if err := xml.Unmarshal(data, &res); err != nil {
		return nil, fmt.Errorf("parsing strings.xml: %w", err)
	}
	m := make(map[string]string, len(res.Strings))
	for _, s := range res.Strings {
		m[s.Name] = androidStringEscapes.Replace(s.Value)
	}
	return m, nil
}

func appString(t *testing.T, name string) string {
	t.Helper()
	path := filepath.Join(repoRoot(t), "android", "src", "main", "res", "values", "strings.xml")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	m, err := parseAppStrings(data)
	if err != nil {
		t.Fatal(err)
	}
	s, ok := m[name]
	if !ok || s == "" {
		t.Fatalf("string resource %q not found in %s", name, path)
	}
	return s
}

func TestAppStrings(t *testing.T) {
	for _, name := range smokeStrings {
		if s := appString(t, name); strings.TrimSpace(s) != s || strings.Contains(s, "\n") {
			t.Errorf("string %q must be a single line without surrounding whitespace to match on screen: %q", name, s)
		}
	}

	m, err := parseAppStrings([]byte(`<resources><string name="a">Don\'t &amp; \"go\"</string></resources>`))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := m["a"], `Don't & "go"`; got != want {
		t.Errorf("parseAppStrings = %q; want %q", got, want)
	}
}
