package org.eclipse.jdt.internal.ui.refactoring;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import org.eclipse.ui.help.WorkbenchHelp;

import org.eclipse.jdt.internal.ui.IJavaHelpContextIds;

import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveInnerToTopRefactoring;

public class MoveInnerToToplnputPage extends TextInputWizardPage{

	private final boolean fIsInitialInputValid;
	private static final String MESSAGE = "Specify a name for the field that will be used to access the enclosing instance";
	
	public MoveInnerToToplnputPage(String initialValue) {
		super(MESSAGE, true, initialValue);
		fIsInitialInputValid= ! ("".equals(initialValue));
	}

	public void createControl(Composite parent) {
		Composite newControl= new Composite(parent, SWT.NONE);
		setControl(newControl);
		WorkbenchHelp.setHelp(newControl, IJavaHelpContextIds.MOVE_INNER_TO_TOP_WIZARD_PAGE);
		newControl.setLayout(new GridLayout());
		
		GridLayout layout= new GridLayout();
		layout.numColumns= 2;
		layout.verticalSpacing= 8;
		newControl.setLayout(layout);
		
		Label label= new Label(newControl, SWT.NONE);
		label.setText("Enter &name for enclosing instance:");
		
		Text text= createTextInputField(newControl);
		text.selectAll();
		text.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
	}

	/*
	 * @see org.eclipse.jdt.internal.ui.refactoring.TextInputWizardPage#validateTextField(String)
	 */
	protected RefactoringStatus validateTextField(String text) {
		getMoveRefactoring().setEnclosingInstanceName(text);
		return getMoveRefactoring().checkEnclosingInstanceName(text);
	}	

	/*
	 * @see org.eclipse.jdt.internal.ui.refactoring.TextInputWizardPage#isInitialInputValid()
	 */
	protected boolean isInitialInputValid() {
		return fIsInitialInputValid;
	}

	private MoveInnerToTopRefactoring getMoveRefactoring() {
		return (MoveInnerToTopRefactoring)getRefactoring();
	}
	
}
